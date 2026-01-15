package io.github.nicolals.nostr.nip42.protocol

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonBoolean
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.JsonValue
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.protocol.ConnectionPhase
import io.github.nicolals.nostr.core.protocol.ConnectionState
import io.github.nicolals.nostr.core.protocol.InboundMessage
import io.github.nicolals.nostr.core.protocol.ProtocolEffect
import io.github.nicolals.nostr.core.protocol.ProtocolState
import io.github.nicolals.nostr.core.protocol.TransportInput
import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.nip01.message.OkMessage
import io.github.nicolals.nostr.nip42.event.AuthEvent
import io.github.nicolals.nostr.nip42.message.AuthChallengeMessage
import io.github.nicolals.nostr.nip42.message.AuthClientMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip42ReducerTest {

    // ===== Test Fixtures =====

    private val stubCodec = object : NostrWireCodec {
        override fun decodeEvent(value: JsonValue) = WireEvent("", "", 0, 0, emptyList(), "", "")
        override fun encodeEvent(event: WireEvent) = JsonArray(emptyList())
        override fun decodeFilter(value: JsonValue) = WireFilter()
        override fun encodeFilter(filter: WireFilter) = JsonArray(emptyList())
        override fun decodeClientMessage(value: JsonValue) = RawClientMessage(JsonArray(emptyList()))
        override fun decodeRelayMessage(value: JsonValue) = RawRelayMessage(JsonArray(emptyList()))
    }

    private val reducer = Nip42Reducer(stubCodec)

    private val testChallenge = "test-challenge-12345"
    private val testRelayUrl = "wss://relay.example.com"

    private val testEventId = EventId.fromHex("a".repeat(64))
    private val testPubkey = PublicKey.fromHex("c".repeat(64))
    private val testSig = EventSig.fromHex("d".repeat(128))

    private val testAuthEvent = Event(
        id = testEventId,
        pubkey = testPubkey,
        createdAt = UnixTimeSeconds(1700000000),
        kind = AuthEvent.KIND,
        tags = listOf(
            listOf("relay", testRelayUrl),
            listOf("challenge", testChallenge),
        ),
        content = "",
        sig = testSig,
    )

    private fun connectedState() = ProtocolState(
        connection = ConnectionState(ConnectionPhase.Open)
    )

    private fun stateWithChallenge(): ProtocolState {
        val authState = Nip42State(
            phase = AuthPhase.ChallengeReceived,
            challenge = testChallenge,
        )
        return connectedState().withModuleStates(
            connectedState().moduleStates.set(Nip42StateKey, authState)
        )
    }

    private fun getAuthState(state: ProtocolState): Nip42State =
        state.moduleStates[Nip42StateKey]

    // ===== AUTH Challenge Tests =====

    @Test
    fun authChallengeTransitionsToChallengeReceived() {
        val state = connectedState()
        val challengeMessage = createAuthChallengeMessage(testChallenge)
        val result = reducer.reduce(state, InboundMessage(challengeMessage))

        assertNotNull(result)
        val authState = getAuthState(result.state)
        assertEquals(AuthPhase.ChallengeReceived, authState.phase)
        assertEquals(testChallenge, authState.challenge)

        val signal = result.signals.filterIsInstance<Nip42Signal.ChallengeReceived>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testChallenge, signal.challenge)
    }

    @Test
    fun newChallengReplacesOldChallenge() {
        // Start with existing challenge
        val state = stateWithChallenge()
        val newChallenge = "new-challenge-67890"
        val challengeMessage = createAuthChallengeMessage(newChallenge)

        val result = reducer.reduce(state, InboundMessage(challengeMessage))

        assertNotNull(result)
        val authState = getAuthState(result.state)
        assertEquals(newChallenge, authState.challenge)
    }

    @Test
    fun challengeDoesNotResetAuthenticatedState() {
        // Already authenticated
        val authenticatedState = Nip42State(
            phase = AuthPhase.Authenticated,
            challenge = "old-challenge",
            authenticatedPubkey = testPubkey,
        )
        val state = connectedState().withModuleStates(
            connectedState().moduleStates.set(Nip42StateKey, authenticatedState)
        )

        val challengeMessage = createAuthChallengeMessage(testChallenge)
        val result = reducer.reduce(state, InboundMessage(challengeMessage))

        assertNotNull(result)
        val authState = getAuthState(result.state)
        // Should still be authenticated
        assertEquals(AuthPhase.Authenticated, authState.phase)
        // But challenge should be updated
        assertEquals(testChallenge, authState.challenge)
        // Should NOT emit ChallengeReceived when already authenticated
        assertTrue(result.signals.filterIsInstance<Nip42Signal.ChallengeReceived>().isEmpty())
    }

    // ===== Authenticate Intent Tests =====

    @Test
    fun authenticateWhenChallengeReceivedSendsAuthMessage() {
        val state = stateWithChallenge()
        val intent = Nip42Intent.Authenticate(testAuthEvent)
        val result = reducer.reduce(state, intent)

        assertNotNull(result)
        val authState = getAuthState(result.state)
        assertEquals(AuthPhase.AuthPending, authState.phase)
        assertEquals(testEventId, authState.pendingAuthEventId)
        assertEquals(testPubkey, authState.pendingAuthPubkey)

        // Should send AUTH message
        assertTrue(result.effects.any { it is ProtocolEffect.Send && it.message is AuthClientMessage })

        // Should emit AuthPending signal
        val signal = result.signals.filterIsInstance<Nip42Signal.AuthPending>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testPubkey, signal.pubkey)
    }

    @Test
    fun authenticateWithoutChallengeDoesNothing() {
        val state = connectedState() // No challenge received
        val intent = Nip42Intent.Authenticate(testAuthEvent)
        val result = reducer.reduce(state, intent)

        assertNotNull(result)
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun authenticateWhenNotConnectedDoesNothing() {
        val state = ProtocolState() // Disconnected
        val intent = Nip42Intent.Authenticate(testAuthEvent)
        val result = reducer.reduce(state, intent)

        assertNotNull(result)
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    // ===== OK Response Tests =====

    @Test
    fun okAcceptedTransitionsToAuthenticated() {
        // Setup: auth pending
        val pendingState = Nip42State(
            phase = AuthPhase.AuthPending,
            challenge = testChallenge,
            pendingAuthEventId = testEventId,
            pendingAuthPubkey = testPubkey,
        )
        val state = connectedState().withModuleStates(
            connectedState().moduleStates.set(Nip42StateKey, pendingState)
        )

        val okMessage = createOkMessage(testEventId, true, "")
        val result = reducer.reduce(state, InboundMessage(okMessage))

        assertNotNull(result)
        val authState = getAuthState(result.state)
        assertEquals(AuthPhase.Authenticated, authState.phase)
        assertEquals(testPubkey, authState.authenticatedPubkey)
        assertNull(authState.pendingAuthEventId)
        assertNull(authState.failureMessage)

        val signal = result.signals.filterIsInstance<Nip42Signal.Authenticated>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testPubkey, signal.pubkey)
    }

    @Test
    fun okRejectedTransitionsToAuthFailed() {
        // Setup: auth pending
        val pendingState = Nip42State(
            phase = AuthPhase.AuthPending,
            challenge = testChallenge,
            pendingAuthEventId = testEventId,
            pendingAuthPubkey = testPubkey,
        )
        val state = connectedState().withModuleStates(
            connectedState().moduleStates.set(Nip42StateKey, pendingState)
        )

        val okMessage = createOkMessage(testEventId, false, "error: invalid signature")
        val result = reducer.reduce(state, InboundMessage(okMessage))

        assertNotNull(result)
        val authState = getAuthState(result.state)
        assertEquals(AuthPhase.AuthFailed, authState.phase)
        assertEquals("error: invalid signature", authState.failureMessage)
        assertNull(authState.pendingAuthEventId)

        val signal = result.signals.filterIsInstance<Nip42Signal.AuthFailed>().firstOrNull()
        assertNotNull(signal)
        assertEquals("error: invalid signature", signal.message)
    }

    @Test
    fun okForDifferentEventIdIsIgnored() {
        // Setup: auth pending for different event
        val pendingState = Nip42State(
            phase = AuthPhase.AuthPending,
            challenge = testChallenge,
            pendingAuthEventId = testEventId,
            pendingAuthPubkey = testPubkey,
        )
        val state = connectedState().withModuleStates(
            connectedState().moduleStates.set(Nip42StateKey, pendingState)
        )

        // OK for different event ID
        val differentEventId = EventId.fromHex("b".repeat(64))
        val okMessage = createOkMessage(differentEventId, true, "")
        val result = reducer.reduce(state, InboundMessage(okMessage))

        // Should return null (not handled by this reducer)
        assertNull(result)
    }

    @Test
    fun okWithNoPendingAuthIsIgnored() {
        val state = connectedState()
        val okMessage = createOkMessage(testEventId, true, "")
        val result = reducer.reduce(state, InboundMessage(okMessage))

        // Should return null (no pending auth to correlate)
        assertNull(result)
    }

    // ===== Disconnect Tests =====

    @Test
    fun disconnectResetsAuthState() {
        // Setup: authenticated state
        val authenticatedState = Nip42State(
            phase = AuthPhase.Authenticated,
            challenge = testChallenge,
            authenticatedPubkey = testPubkey,
        )
        val state = connectedState().withModuleStates(
            connectedState().moduleStates.set(Nip42StateKey, authenticatedState)
        )

        val result = reducer.reduce(state, TransportInput.Closed(CloseInfo(1000, "normal", false)))

        assertNotNull(result)
        val authState = getAuthState(result.state)
        assertEquals(AuthPhase.Unauthenticated, authState.phase)
        assertNull(authState.challenge)
        assertNull(authState.authenticatedPubkey)

        val signal = result.signals.filterIsInstance<Nip42Signal.AuthReset>().firstOrNull()
        assertNotNull(signal)
    }

    @Test
    fun disconnectWithNoAuthStateDoesNotEmitSignal() {
        val state = connectedState() // No auth state set
        val result = reducer.reduce(state, TransportInput.Closed(CloseInfo(1000, "normal", false)))

        // Should return null (no auth state to reset)
        assertNull(result)
    }

    @Test
    fun transportFailedResetsAuthState() {
        // Setup: pending auth state
        val pendingState = Nip42State(
            phase = AuthPhase.AuthPending,
            challenge = testChallenge,
            pendingAuthEventId = testEventId,
            pendingAuthPubkey = testPubkey,
        )
        val state = connectedState().withModuleStates(
            connectedState().moduleStates.set(Nip42StateKey, pendingState)
        )

        val result = reducer.reduce(state, TransportInput.Failed(
            io.github.nicolals.nostr.core.transport.TransportError.IoError("connection lost")
        ))

        assertNotNull(result)
        val authState = getAuthState(result.state)
        assertEquals(AuthPhase.Unauthenticated, authState.phase)

        assertTrue(result.signals.any { it is Nip42Signal.AuthReset })
    }

    // ===== Unhandled Input Test =====

    @Test
    fun unhandledInputReturnsNull() {
        val state = connectedState()
        val customInput = object : io.github.nicolals.nostr.core.protocol.ProtocolInput {}
        val result = reducer.reduce(state, customInput)

        assertNull(result)
    }

    // ===== Test Message Factories =====

    private fun createAuthChallengeMessage(challenge: String): AuthChallengeMessage {
        val array = JsonArray(listOf(JsonString("AUTH"), JsonString(challenge)))
        return AuthChallengeMessage(RawRelayMessage(array), challenge)
    }

    private fun createOkMessage(eventId: EventId, accepted: Boolean, message: String): OkMessage {
        val array = JsonArray(listOf(
            JsonString("OK"),
            JsonString(eventId.hex),
            JsonBoolean(accepted),
            JsonString(message)
        ))
        return OkMessage(RawRelayMessage(array), eventId.hex, eventId, accepted, message)
    }
}
