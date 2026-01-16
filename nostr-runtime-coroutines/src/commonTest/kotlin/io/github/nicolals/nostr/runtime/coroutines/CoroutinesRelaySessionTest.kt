package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.runtime.ConnectionStatus
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.runtime.ReconnectDecision
import io.github.nicolals.nostr.core.runtime.ReconnectPolicy
import io.github.nicolals.nostr.core.runtime.SessionConfig
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesRelaySessionTest {

    private val testUrl = RelayUrl.parse("wss://test.relay")
    private val testModuleContext = createTestModuleContext()

    // ===== Connection Lifecycle Tests =====

    @Test
    fun initialConnectionStatusIsDisconnected() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        assertEquals(ConnectionStatus.Disconnected, session.connectionStatus)
        session.close()
    }

    @Test
    fun connectChangesStatusToConnecting() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        assertEquals(ConnectionStatus.Connecting, session.connectionStatus)
        session.close()
    }

    @Test
    fun transportOpenChangesStatusToConnected() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        assertEquals(ConnectionStatus.Connected, session.connectionStatus)
        session.close()
    }

    @Test
    fun disconnectClosesTransport() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        session.disconnect(1000, "test disconnect")
        advanceUntilIdle()

        assertEquals(1000, factory.lastConnection?.closeCode)
        assertEquals("test disconnect", factory.lastConnection?.closeReason)
        session.close()
    }

    @Test
    fun closeStopsSession() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        session.close()
        advanceUntilIdle()

        assertEquals(ConnectionStatus.Disconnected, session.connectionStatus)
    }

    // ===== Reconnection Tests =====

    @Test
    fun transportFailureTriggersReconnect() = runTest {
        val factory = FakeRelayTransportFactory()
        val config = SessionConfig(
            reconnectPolicy = ReconnectPolicy.exponentialBackoff(
                baseDelayMs = 10_000, // Long delay so we can observe Reconnecting state
                maxDelayMs = 30_000,
                maxAttempts = 3,
            ),
        )
        val session = createSession(factory, config)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        assertEquals(ConnectionStatus.Connected, session.connectionStatus)

        // Simulate connection failure
        factory.lastConnection?.simulateFailure(TransportError.IoError("connection lost"))
        // Only advance a small amount, not through the reconnect delay
        advanceTimeBy(100)

        val status = session.connectionStatus
        assertIs<ConnectionStatus.Reconnecting>(status)
        assertEquals(1, status.attempt)

        session.close()
    }

    @Test
    fun reconnectPolicyNeverDoesNotReconnect() = runTest {
        val factory = FakeRelayTransportFactory()
        val config = SessionConfig(
            reconnectPolicy = ReconnectPolicy.never(),
        )
        val session = createSession(factory, config)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        // Simulate connection failure
        val error = TransportError.IoError("connection lost")
        factory.lastConnection?.simulateFailure(error)
        advanceUntilIdle()

        val status = session.connectionStatus
        assertIs<ConnectionStatus.Failed>(status)
        assertEquals(error, status.error)

        session.close()
    }

    @Test
    fun intentionalDisconnectDoesNotReconnect() = runTest {
        val factory = FakeRelayTransportFactory()
        val config = SessionConfig(
            reconnectPolicy = ReconnectPolicy.exponentialBackoff(maxAttempts = 3),
        )
        val session = createSession(factory, config)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        // Intentional disconnect
        session.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionStatus.Disconnected, session.connectionStatus)
        session.close()
    }

    // ===== Subscription Tests =====

    @Test
    fun subscribeReturnsHandle() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        assertEquals(TestFixtures.testSubId, handle.subscriptionId)
        assertEquals(SubscriptionStatus.CatchingUp, handle.status)

        session.close()
    }

    @Test
    fun subscribeSendsReqMessage() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        factory.lastConnection?.clearSentMessages()

        session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        assertTrue(factory.lastConnection?.sentMessages?.isNotEmpty() == true)
        val message = factory.lastConnection?.sentMessages?.first() ?: ""
        assertTrue(message.contains("REQ"))
        assertTrue(message.contains(TestFixtures.testSubId.value))

        session.close()
    }

    @Test
    fun eoseTransitionsToLive() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        // Simulate EOSE
        factory.lastConnection?.simulateMessage(TestFixtures.eoseJson(TestFixtures.testSubId))
        advanceUntilIdle()

        assertEquals(SubscriptionStatus.Live, handle.status)

        session.close()
    }

    @Test
    fun eventsAreDeliveredThroughHandle() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        // Collect events
        val events = mutableListOf<io.github.nicolals.nostr.core.event.Event>()
        val collectJob = launch {
            handle.events.collect { events.add(it) }
        }

        // Simulate event
        factory.lastConnection?.simulateMessage(TestFixtures.eventJson(TestFixtures.testSubId, TestFixtures.testEvent))
        advanceUntilIdle()

        assertEquals(1, events.size)
        assertEquals(TestFixtures.testEventId, events[0].id)

        collectJob.cancel()
        session.close()
    }

    @Test
    fun closeSubscriptionSendsCloseMessage() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        factory.lastConnection?.clearSentMessages()

        handle.close()
        advanceUntilIdle()

        assertTrue(factory.lastConnection?.sentMessages?.any { it.contains("CLOSE") } == true)
        assertIs<SubscriptionStatus.Closed>(handle.status)

        session.close()
    }

    @Test
    fun subscriptionClosedWhenReconnectPolicyGivesUp() = runTest {
        val factory = FakeRelayTransportFactory()
        val config = SessionConfig(
            reconnectPolicy = ReconnectPolicy.never(),
        )
        val session = createSession(factory, config)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        // Simulate connection failure - with never() policy, subscription should be closed
        factory.lastConnection?.simulateFailure(TransportError.IoError("lost"))
        advanceUntilIdle()

        // With ReconnectPolicy.never(), subscription is permanently closed, not just interrupted
        assertIs<SubscriptionStatus.Closed>(handle.status)

        session.close()
    }

    @Test
    fun subscriptionInterruptedDuringReconnect() = runTest {
        val factory = FakeRelayTransportFactory()
        val config = SessionConfig(
            reconnectPolicy = ReconnectPolicy.exponentialBackoff(
                baseDelayMs = 10_000, // Long delay so we stay in Reconnecting state
                maxAttempts = 3,
            ),
        )
        val session = createSession(factory, config)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        // Simulate connection failure - with reconnect policy, subscription should be interrupted
        factory.lastConnection?.simulateFailure(TransportError.IoError("lost"))
        advanceTimeBy(100) // Not enough time to reconnect

        // With reconnect policy, subscription is interrupted (will resume on reconnect)
        assertEquals(SubscriptionStatus.Interrupted, handle.status)

        session.close()
    }

    // ===== Publish Tests =====

    @Test
    fun publishReturnsHandle() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.publish(TestFixtures.testEvent)
        advanceUntilIdle()

        assertEquals(TestFixtures.testEventId, handle.eventId)
        assertEquals(PublishStatus.Pending, handle.status)

        session.close()
    }

    @Test
    fun publishSendsEventMessage() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        factory.lastConnection?.clearSentMessages()

        session.publish(TestFixtures.testEvent)
        advanceUntilIdle()

        assertTrue(factory.lastConnection?.sentMessages?.isNotEmpty() == true)
        val message = factory.lastConnection?.sentMessages?.first() ?: ""
        assertTrue(message.contains("EVENT"))
        assertTrue(message.contains(TestFixtures.testEventId.hex))

        session.close()
    }

    @Test
    fun okAcceptedTransitionsToAccepted() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.publish(TestFixtures.testEvent)
        advanceUntilIdle()

        // Simulate OK accepted
        factory.lastConnection?.simulateMessage(TestFixtures.okJson(TestFixtures.testEventId, true, ""))
        advanceUntilIdle()

        val status = handle.status
        assertIs<PublishStatus.Accepted>(status)

        session.close()
    }

    @Test
    fun okRejectedTransitionsToRejected() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.publish(TestFixtures.testEvent)
        advanceUntilIdle()

        // Simulate OK rejected
        factory.lastConnection?.simulateMessage(TestFixtures.okJson(TestFixtures.testEventId, false, "error: invalid"))
        advanceUntilIdle()

        val status = handle.status
        assertIs<PublishStatus.Rejected>(status)
        assertEquals("error: invalid", status.message)

        session.close()
    }

    @Test
    fun awaitReturnsTerminalStatus() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.publish(TestFixtures.testEvent)
        advanceUntilIdle()

        // Launch await in background
        val awaitJob = launch {
            val result = handle.await()
            assertIs<PublishStatus.Accepted>(result)
        }

        // Simulate OK
        factory.lastConnection?.simulateMessage(TestFixtures.okJson(TestFixtures.testEventId, true, ""))
        advanceUntilIdle()

        awaitJob.join()

        session.close()
    }

    @Test
    fun publishAbandonedWhenReconnectGivesUp() = runTest {
        val factory = FakeRelayTransportFactory()
        val config = SessionConfig(
            reconnectPolicy = ReconnectPolicy.never(),
        )
        val session = createSession(factory, config)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.publish(TestFixtures.testEvent)
        advanceUntilIdle()

        // Simulate connection failure - with never() policy, gives up immediately
        factory.lastConnection?.simulateFailure(TransportError.IoError("lost"))
        advanceUntilIdle()

        // With never() policy, publish is abandoned (not just interrupted)
        assertEquals(PublishStatus.Abandoned, handle.status)

        session.close()
    }

    // ===== Deduplication Tests =====

    @Test
    fun duplicateEventsAreFiltered() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = createSession(factory)

        session.connect()
        advanceUntilIdle()

        factory.lastConnection?.simulateOpen()
        advanceUntilIdle()

        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        advanceUntilIdle()

        // Collect events
        val events = mutableListOf<io.github.nicolals.nostr.core.event.Event>()
        val collectJob = launch {
            handle.events.collect { events.add(it) }
        }

        // Simulate same event twice
        factory.lastConnection?.simulateMessage(TestFixtures.eventJson(TestFixtures.testSubId, TestFixtures.testEvent))
        advanceUntilIdle()

        factory.lastConnection?.simulateMessage(TestFixtures.eventJson(TestFixtures.testSubId, TestFixtures.testEvent))
        advanceUntilIdle()

        // Should only receive one event
        assertEquals(1, events.size)

        collectJob.cancel()
        session.close()
    }

    // ===== Helper Methods =====

    private fun TestScope.createSession(
        factory: FakeRelayTransportFactory,
        config: SessionConfig = SessionConfig.Default,
    ): CoroutinesRelaySession {
        return CoroutinesRelaySession(
            url = testUrl,
            moduleContext = testModuleContext,
            transportFactory = factory,
            scope = this,
            config = config,
        )
    }
}
