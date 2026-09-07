package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.message.view.RelayMessageView
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipRegistry
import io.github.nicolals.nostr.nip01.Nip01Module
import io.github.nicolals.nostr.core.protocol.*
import io.github.nicolals.nostr.core.runtime.ConnectionStatus
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.nip01.message.RelayEventMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSafetyTest {
    @Test fun sessionAndPoolRejectMissingBaseAndDuplicateBaseModulesBeforeStarting() = runTest {
        val codec = TestWireCodec()
        val empty = NipRegistry.Builder(codec).build()
        val factory = FakeRelayTransportFactory()
        assertFailsWith<IllegalArgumentException> {
            CoroutinesRelaySession(url, empty, codec, AcceptTestEvents, factory, this)
        }
        assertFailsWith<IllegalArgumentException> {
            CoroutinesRelayPool(empty, codec, AcceptTestEvents, factory, this)
        }
        assertFailsWith<IllegalArgumentException> {
            CoroutinesRelaySession(url, codec, AcceptTestEvents, factory, this,
                additionalModules = listOf(Nip01Module(codec)))
        }
        assertTrue(factory.connections.isEmpty())
    }
    private val url = RelayUrl.parse("wss://test.relay")

    private fun TestScope.connect(session: CoroutinesRelaySession, factory: FakeRelayTransportFactory) {
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
    }

    private data class WrappedEvent(override val raw: RawRelayMessage) : RelayMessageView

    @Test fun standardEventVerificationAndRoutingCannotBeShadowed() = runTest {
        var upgrades = 0
        val wrapper = object : NipModule {
        override val id = "test.SessionSafetyTest.1"
            override val relayMessageUpgraders = listOf(object : RelayMessageUpgrader<WrappedEvent> {
                override val tokens = setOf("EVENT")
                override val priority = 100
                override fun upgradeOrNull(raw: RawRelayMessage, token: String): WrappedEvent {
                    upgrades++
                    return WrappedEvent(raw)
                }
            })
        }
        var checks = 0
        val verifier = EventVerifier {
            if (++checks == 1) CryptoResult.Err(CryptoError.VerificationFailed()) else CryptoResult.Ok(Unit)
        }
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(url, TestWireCodec(), verifier, factory, this,
            additionalModules = listOf(wrapper))
        connect(session, factory)
        val subscription = session.subscribe(TestFixtures.testFilters)
        val messages = mutableListOf<RelayMessageView>()
        val events = mutableListOf<io.github.nicolals.nostr.core.event.Event>()
        launch(start = CoroutineStart.UNDISPATCHED) { session.messages.collect { messages += it } }
        launch(start = CoroutineStart.UNDISPATCHED) { subscription.events.collect { events += it } }
        runCurrent()
        val text = TestFixtures.eventJson(subscription.subscriptionId, TestFixtures.testEvent)
        factory.lastConnection!!.simulateMessage(text)
        runCurrent()
        assertTrue(messages.isEmpty())
        assertTrue(events.isEmpty())
        factory.lastConnection!!.simulateMessage(text)
        runCurrent()
        assertEquals(2, checks)
        assertEquals(0, upgrades)
        assertIs<RelayEventMessage>(messages.single())
        assertEquals(listOf(TestFixtures.testEvent), events)
        session.closeAndJoin()
    }

    @Test fun reducerFailureOnDisconnectTerminatesSessionAndPendingHandles() = runTest {
        assertReducerFailureCloses { it is TransportInput.Closed }
    }

    @Test fun reducerFailureOnInboundMessageIsNotSwallowedAsParseError() = runTest {
        assertReducerFailureCloses { it is InboundMessage }
    }

    private suspend fun TestScope.assertReducerFailureCloses(shouldThrow: (ProtocolInput) -> Boolean) {
        val faulty = object : NipModule {
        override val id = "test.SessionSafetyTest.2"
            override val protocolReducers = listOf(object : ProtocolReducer {
                override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                    if (shouldThrow(input)) error("private extension details")
                    return null
                }
            })
        }
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(url, TestWireCodec(), AcceptTestEvents, factory, this,
            additionalModules = listOf(faulty))
        connect(session, factory)
        val publish = session.publish(TestFixtures.testEvent)
        val subscription = session.subscribe(TestFixtures.testFilters)
        runCurrent()
        factory.lastConnection!!.simulateMessage(TestFixtures.noticeJson("hello"))
        runCurrent()
        if (!session.isClosed) factory.lastConnection!!.simulateClosed()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(session.isClosed)
        assertIs<ConnectionStatus.Failed>(session.connectionStatus)
        assertEquals(PublishStatus.Abandoned, publish.status)
        assertIs<SubscriptionStatus.Closed>(subscription.status)
        assertEquals(1, factory.connections.size)
        session.closeAndJoin()
    }
}
