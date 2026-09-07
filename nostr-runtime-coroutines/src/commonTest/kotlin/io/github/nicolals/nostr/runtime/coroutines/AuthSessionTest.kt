package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.protocol.AdmissionDecision
import io.github.nicolals.nostr.core.protocol.OperationAdmission
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.nip01.protocol.Nip01Intent
import io.github.nicolals.nostr.nip42.Nip42Module
import io.github.nicolals.nostr.nip42.event.AuthEvent
import io.github.nicolals.nostr.nip42.protocol.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionTest {
    @Test fun authAdmissionRejectsStalePreparationAndResumesAfterAcknowledgment() = runTest {
        val codec = TestWireCodec()
        val factory = FakeRelayTransportFactory()
        val gate = object : NipModule {
            override val id = "test.auth.policy"
            override val requires = setOf("nip42")
            override val admissionPolicies = listOf(OperationAdmission { state, intent ->
                if ((intent is Nip01Intent.Publish || intent is Nip01Intent.Subscribe) &&
                    !state.moduleStates[Nip42StateKey].isAuthenticated
                ) AdmissionDecision.Defer("Authentication required") else AdmissionDecision.Allow
            })
        }
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://auth.relay"), codec, AcceptTestEvents, factory, this,
            additionalModules = listOf(Nip42Module(codec), gate),
        )
        val challenges = mutableListOf<AuthChallenge>()
        val rejected = mutableListOf<AuthRejectionReason>()
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.signals.collect {
                when (it) {
                    is Nip42Signal.ChallengeReceived -> challenges += it.challenge
                    is Nip42Signal.AuthResponseRejected -> rejected += it.reason
                }
            }
        }
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        val subscription = session.subscribe(TestFixtures.testFilters)
        val publish = session.publish(TestFixtures.testEvent)
        runCurrent()
        assertIs<SubscriptionStatus.Deferred>(subscription.status)
        assertIs<PublishStatus.Deferred>(publish.status)

        factory.lastConnection!!.simulateMessage("[\"AUTH\",\"repeated\"]")
        runCurrent()
        val old = challenges.last()
        factory.lastConnection!!.simulateMessage("[\"AUTH\",\"repeated\"]")
        runCurrent()
        session.submit(Nip42Intent.Authenticate(response(old), old))
        runCurrent()
        assertEquals(listOf(AuthRejectionReason.StaleChallenge), rejected)
        assertTrue(factory.lastConnection!!.sentMessages.isEmpty())

        val current = challenges.last()
        session.submit(Nip42Intent.Authenticate(response(current), current))
        assertEquals(1, factory.lastConnection!!.sentMessages.size)
        factory.lastConnection!!.simulateMessage(TestFixtures.okJson(TestFixtures.testEventId2, true))
        runCurrent()
        assertEquals(3, factory.lastConnection!!.sentMessages.size)
        assertEquals(SubscriptionStatus.CatchingUp, subscription.status)
        assertEquals(PublishStatus.Pending, publish.status)

        // A response from a previous connection cannot authenticate the next one,
        // even when the relay repeats the exact challenge text.
        factory.lastConnection!!.simulateClosed()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        factory.lastConnection!!.simulateMessage("[\"AUTH\",\"repeated\"]")
        runCurrent()
        session.submit(Nip42Intent.Authenticate(response(current), current))
        runCurrent()
        assertEquals(2, rejected.size)
        assertTrue(factory.lastConnection!!.sentMessages.isEmpty())
        val fresh = challenges.last()
        session.submit(Nip42Intent.Authenticate(response(fresh), fresh))
        assertEquals(1, factory.lastConnection!!.sentMessages.size)
        session.closeAndJoin()
    }

    private fun response(challenge: AuthChallenge) = TestFixtures.testEvent2.copy(
        kind = AuthEvent.KIND,
        content = "",
        tags = listOf(listOf("relay", challenge.relayUrl.value), listOf("challenge", challenge.value)),
    )
}
