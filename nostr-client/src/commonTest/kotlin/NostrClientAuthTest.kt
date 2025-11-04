package io.github.nicolals.nostr.client

import io.github.nicolals.nostr.nips.nip42.Nip42Auth
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nostr.core.model.Event
import nostr.core.model.PublishResult
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.Nip42AuthAttempt
import nostr.core.session.Nip42AuthState
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NostrClientAuthTest {

    @Test
    fun signerProviderBuildsEvent() = runTest {
        val signer = object : nostr.core.crypto.Signer {
            override val publicKey = nostr.core.crypto.keys.PublicKey.fromHex("a".repeat(64))
            override fun sign(eventId: nostr.core.crypto.event.EventId): nostr.core.crypto.event.Signature =
                nostr.core.crypto.event.Signature.fromHex(eventId.toString() + eventId.toString())
        }
        val provider = SignerNip42AuthProvider(
            signer = signer,
            contentProvider = { _, challenge -> "content-$challenge" },
            additionalTagsProvider = { relay, _ -> listOf(listOf("client", relay)) }
        )
        val event = provider.buildAuthEvent("wss://relay", "challenge")
        assertEquals(22242, event.kind)
        assertTrue(event.tags.contains(listOf("client", "wss://relay")))
        assertEquals("content-challenge", event.content)
    }

    @Test
    fun autoAuthenticatorRespondsToChallenge() = runTest {
        val handle = FakeSessionHandle(url = "wss://relay", scope = this)
        val provider = RecordingProvider()
        val authenticator = Nip42AutoAuthenticator(this, handle, provider)

        handle.emitState { state ->
            state.copy(
                auth = state.auth.copy(challenge = "token"),
                connection = ConnectionSnapshot.Connected(handle.url)
            )
        }

        advanceUntilIdle()

        assertEquals(listOf("token"), provider.challenges)
        assertEquals(1, handle.authenticated.size)

        authenticator.stop()
    }

    @Test
    fun autoAuthenticatorRetriesOnAuthRequired() = runTest {
        val handle = FakeSessionHandle(url = "wss://relay", scope = this)
        val provider = RecordingProvider()
        val authenticator = Nip42AutoAuthenticator(this, handle, provider)

        handle.emitState { state ->
            state.copy(
                auth = state.auth.copy(challenge = "token"),
                connection = ConnectionSnapshot.Connected(handle.url)
            )
        }
        advanceUntilIdle()

        // Simulate relay acknowledging authentication
        val authEvent = handle.authenticated.first()
        handle.emitState { state ->
            state.copy(
                auth = state.auth.copy(
                    challenge = "token",
                    latestAttempt = Nip42AuthAttempt("token", authEvent.id, accepted = true, message = null)
                )
            )
        }

        // Relay rejects a publish with auth-required
        handle.emitOutput(
            RelaySessionOutput.PublishAcknowledged(
                PublishResult(eventId = "note", accepted = false, message = "auth required", code = "auth-required")
            )
        )
        advanceUntilIdle()

        assertEquals(2, provider.challenges.size)
        authenticator.stop()
    }

    private class RecordingProvider : Nip42AuthProvider {
        private var counter = 0
        val challenges = mutableListOf<String>()
        override suspend fun buildAuthEvent(relayUrl: String, challenge: String): Event {
            challenges += challenge
            counter += 1
            val unsigned = Nip42Auth.buildUnsignedAuthEvent(
                pubkey = "a".repeat(64),
                relayUrl = relayUrl,
                challenge = challenge,
                createdAt = counter.toLong()
            )
            return Event(
                id = counter.toString(16).padStart(64, '0'),
                pubkey = unsigned.pubkey,
                createdAt = unsigned.createdAt,
                kind = unsigned.kind,
                tags = unsigned.tags,
                content = unsigned.content,
                sig = "b".repeat(128)
            )
        }
    }

    private class FakeSessionHandle(
        override val url: String,
        private val scope: TestScope
    ) : Nip42SessionHandle {
        private val stateFlow = MutableStateFlow(
            RelaySessionState(
                connection = ConnectionSnapshot.Connected(url)
            )
        )
        private val connectionFlow = MutableStateFlow<ConnectionSnapshot>(ConnectionSnapshot.Connected(url))
        private val outputFlow = MutableSharedFlow<RelaySessionOutput>(extraBufferCapacity = 8)
        val authenticated = mutableListOf<Event>()

        override val outputs = outputFlow
        override val states = stateFlow
        override val connectionSnapshots = connectionFlow

        override suspend fun authenticate(event: Event) {
            authenticated += event
            val challenge = event.tags.firstOrNull { it.firstOrNull() == "challenge" }?.getOrNull(1) ?: return
            stateFlow.value = stateFlow.value.copy(
                auth = Nip42AuthState(
                    challenge = challenge,
                    latestAttempt = Nip42AuthAttempt(challenge, event.id, accepted = null, message = null)
                )
            )
        }

        fun emitState(transform: (RelaySessionState) -> RelaySessionState) {
            val next = transform(stateFlow.value)
            stateFlow.value = next
            connectionFlow.value = next.connection
        }

        suspend fun emitOutput(output: RelaySessionOutput) {
            outputFlow.emit(output)
        }
    }
}
