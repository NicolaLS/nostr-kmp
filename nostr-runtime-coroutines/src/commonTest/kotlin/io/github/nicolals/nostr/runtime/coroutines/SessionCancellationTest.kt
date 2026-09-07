package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.view.UnknownClientMessage
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.protocol.*
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.nip01.protocol.Nip01Intent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCancellationTest {
    private val message = UnknownClientMessage(RawClientMessage(JsonArray(listOf(JsonString("CUSTOM")))))

    private fun TestScope.connected(modules: List<NipModule> = emptyList()): Pair<CoroutinesRelaySession, FakeRelayTransportFactory> {
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), TestWireCodec(), AcceptTestEvents, factory, this,
            additionalModules = modules,
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        return session to factory
    }

    @Test fun cancellingQueuedSubmitAndSendPreventsTransportEffects() = runTest {
        val (session, factory) = connected()
        val submit = launch(start = CoroutineStart.UNDISPATCHED) { session.submit(Nip01Intent.Publish(TestFixtures.testEvent)) }
        val send = launch(start = CoroutineStart.UNDISPATCHED) { session.send(message) }
        submit.cancel()
        send.cancel()
        runCurrent()
        assertTrue(factory.lastConnection!!.sentMessages.isEmpty())
        session.send(message)
        assertEquals(1, factory.lastConnection!!.sentMessages.size)
        session.closeAndJoin()
    }

    @Test fun cancellationAfterActorAdmissionDoesNotClaimToRecallSend() = runTest {
        val (session, factory) = connected()
        val send = launch(start = CoroutineStart.UNDISPATCHED) { session.send(message) }
        factory.lastConnection!!.onSend = { send.cancel() }
        runCurrent()
        assertTrue(send.isCancelled)
        assertEquals(1, factory.lastConnection!!.sentMessages.size)
        session.closeAndJoin()
    }

    private object Gate : ModuleStateKey<Boolean> {
        override val id = "test.cancel.gate"
        override val default = false
    }
    private data object Release : ProtocolIntent

    @Test fun cancelledQueuedAndDeferredPublishesNeverResume() = runTest {
        val gate = object : NipModule {
            override val id = "test.cancel"
            override val admissionPolicies = listOf(OperationAdmission { state, _ ->
                if (state.moduleStates[Gate]) AdmissionDecision.Allow else AdmissionDecision.Defer("Waiting")
            })
            override val protocolReducers = listOf(object : ProtocolReducer {
                override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? =
                    if (input == Release) ReduceResult(state.withModuleStates(state.moduleStates.set(Gate, true))) else null
            })
        }
        val (session, factory) = connected(listOf(gate))
        val queued = session.publish(TestFixtures.testEvent)
        queued.cancel()
        runCurrent()
        assertEquals(PublishStatus.Cancelled(false), queued.status)
        val deferred = session.publish(TestFixtures.testEvent)
        runCurrent()
        assertIs<PublishStatus.Deferred>(deferred.status)
        deferred.cancel()
        session.submit(Release)
        assertEquals(PublishStatus.Cancelled(false), deferred.status)
        assertTrue(factory.lastConnection!!.sentMessages.isEmpty())
        session.closeAndJoin()
    }

    @Test fun cancellingPendingPublishStopsRetriesAndPreservesUncertainty() = runTest {
        val (session, factory) = connected()
        val handle = session.publish(TestFixtures.testEvent)
        runCurrent()
        handle.cancel()
        runCurrent()
        assertEquals(PublishStatus.Cancelled(true), handle.status)
        factory.lastConnection!!.simulateMessage(TestFixtures.okJson(handle.eventId, true))
        factory.lastConnection!!.simulateClosed()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        assertEquals(1, factory.connections.sumOf { it.sentMessages.size })
        assertEquals(PublishStatus.Cancelled(true), handle.status)
        session.closeAndJoin()
    }
}
