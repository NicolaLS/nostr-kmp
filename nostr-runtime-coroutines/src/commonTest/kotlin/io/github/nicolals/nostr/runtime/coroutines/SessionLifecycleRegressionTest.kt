package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.runtime.*
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.transport.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleRegressionTest {
    private fun TestScope.session(factory: FakeRelayTransportFactory, config: SessionConfig = SessionConfig.Default) =
        CoroutinesRelaySession(RelayUrl.parse("wss://review.relay"), TestWireCodec(), AcceptTestEvents, factory, this, config)

    private fun TestScope.connect(session: CoroutinesRelaySession, factory: FakeRelayTransportFactory) {
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
    }

    @Test fun overlappingSubscriptionsBothReceiveEvent() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory, SessionConfig())
        connect(session, factory)
        val first = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        val second = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId2)
        var firstCount = 0
        var secondCount = 0
        val job1 = launch { first.events.collect { firstCount++ } }
        val job2 = launch { second.events.collect { secondCount++ } }
        runCurrent()
        factory.lastConnection!!.simulateMessage(TestFixtures.eventJson(first.subscriptionId, TestFixtures.testEvent))
        factory.lastConnection!!.simulateMessage(TestFixtures.eventJson(second.subscriptionId, TestFixtures.testEvent))
        runCurrent()
        session.closeAndJoin()
        job1.cancel()
        job2.cancel()
        assertEquals(1, firstCount)
        assertEquals(1, secondCount, "Independent overlapping subscription lost its first event")
    }

    @Test fun defaultSessionsHaveIndependentDeduplication() = runTest {
        val factory1 = FakeRelayTransportFactory()
        val factory2 = FakeRelayTransportFactory()
        val first = session(factory1)
        val second = session(factory2)
        connect(first, factory1)
        connect(second, factory2)
        val handle1 = first.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        val handle2 = second.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        var firstCount = 0
        var secondCount = 0
        val job1 = launch { handle1.events.collect { firstCount++ } }
        val job2 = launch { handle2.events.collect { secondCount++ } }
        runCurrent()
        val message = TestFixtures.eventJson(TestFixtures.testSubId, TestFixtures.testEvent)
        factory1.lastConnection!!.simulateMessage(message)
        runCurrent()
        factory2.lastConnection!!.simulateMessage(message)
        runCurrent()
        first.closeAndJoin()
        second.closeAndJoin()
        job1.cancel()
        job2.cancel()
        assertEquals(1, firstCount)
        assertEquals(1, secondCount, "Default configuration shared mutable dedup state across sessions")
    }

    @Test fun delayedSubscriptionRetrySendsReqAfterDelay() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory, SessionConfig(retryPolicy = RetryPolicy.always(100)))
        connect(session, factory)
        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        runCurrent()
        factory.lastConnection!!.simulateMessage(TestFixtures.closedJson(handle.subscriptionId, "retry later"))
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        val reqCount = factory.lastConnection!!.sentMessages.count { it.contains("REQ") }
        val statusBeforeClose = handle.status
        session.closeAndJoin()
        assertEquals(2, reqCount, "Status after ten retry intervals: $statusBeforeClose")
    }

    @Test fun failureFollowedByClosedSchedulesOnlyOneReconnect() = runTest {
        val attempts = mutableListOf<Pair<Int, TransportError?>>()
        val factory = FakeRelayTransportFactory()
        val policy = ReconnectPolicy { attempt, error ->
            attempts.add(attempt to error)
            ReconnectDecision.Reconnect(1000)
        }
        val session = session(factory, SessionConfig(reconnectPolicy = policy))
        connect(session, factory)
        val connection = factory.lastConnection!!
        connection.simulateFailure(TransportError.IoError("lost"))
        connection.simulateClosed(CloseInfo.ABNORMAL_CLOSURE)
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        val connectionCount = factory.connections.size
        session.closeAndJoin()
        assertEquals(1, attempts.size, "One failed connection produced reconnect decisions: $attempts; total connections: $connectionCount")
    }

    @Test fun closeIsNotDroppedByFullMailbox() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory)
        repeat(1000) { session.publish(TestFixtures.testEvent) }
        session.close()
        runCurrent()
        val closed = session.isClosed
        session.closeAndJoin()
        assertTrue(closed, "A saturated mailbox dropped the close request")
    }

    @Test fun publishRejectedByFullMailboxEventuallyBecomesTerminal() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory)
        repeat(1000) { session.publish(TestFixtures.testEvent) }
        val handle = session.publish(TestFixtures.testEvent)
        runCurrent()
        session.closeAndJoin()
        assertTrue(handle.status.isTerminal, "Dropped publish remained ${handle.status} even after session shutdown")
    }

    @Test fun parentCancellationClosesConnectionAndPendingHandles() = runTest {
        val owner = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://review.relay"), TestWireCodec(), AcceptTestEvents, factory, owner,
        )
        connect(session, factory)
        val subscription = session.subscribe(TestFixtures.testFilters)
        val publication = session.publish(TestFixtures.testEvent)
        runCurrent()
        owner.cancel()
        runCurrent()
        session.closeAndJoin()
        assertTrue(subscription.status.isTerminal)
        assertTrue(publication.status.isTerminal)
        assertEquals(TransportState.Closed, factory.lastConnection!!.state)
    }

    @Test fun alreadyCancelledScopeRejectsNewOperations() = runTest {
        val owner = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        owner.cancel()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://review.relay"), TestWireCodec(), AcceptTestEvents,
            FakeRelayTransportFactory(), owner,
        )
        runCurrent()
        session.closeAndJoin()
        assertTrue(session.subscribe(TestFixtures.testFilters).status.isTerminal)
        assertTrue(session.publish(TestFixtures.testEvent).status.isTerminal)
    }

    @Test fun staleCallbacksCannotCloseOrDeliverThroughReplacementConnection() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory, SessionConfig(reconnectPolicy = ReconnectPolicy { _, _ ->
            ReconnectDecision.Reconnect(100)
        }))
        connect(session, factory)
        val oldListener = factory.lastConnection!!.captureListener()
        factory.lastConnection!!.simulateFailure(TransportError.IoError("lost"))
        advanceTimeBy(100)
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        var count = 0
        val collector = launch { handle.events.collect { count++ } }
        runCurrent()
        oldListener.onMessage(TestFixtures.eventJson(handle.subscriptionId, TestFixtures.testEvent))
        oldListener.onClosed(CloseInfo(1000, null, true))
        runCurrent()
        assertEquals(ConnectionStatus.Connected, session.connectionStatus)
        assertEquals(0, count)
        collector.cancel()
        session.closeAndJoin()
    }

    @Test fun replacedHandleCannotCloseNewSubscriptionWithSameId() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory)
        connect(session, factory)
        val previous = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        runCurrent()
        val replacement = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        runCurrent()
        previous.close()
        runCurrent()
        assertFalse(replacement.status.isTerminal)
        session.closeAndJoin()
    }

    @Test fun sendFailureLeavesPublishInterruptedUntilRetry() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory, SessionConfig(reconnectPolicy = ReconnectPolicy { _, _ ->
            ReconnectDecision.Reconnect(100)
        }))
        connect(session, factory)
        factory.lastConnection!!.sendError = TransportError.Backpressure("send")
        val handle = session.publish(TestFixtures.testEvent)
        runCurrent()
        assertEquals(PublishStatus.Interrupted, handle.status)
        advanceTimeBy(100)
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(PublishStatus.Pending, handle.status)
        assertEquals(1, factory.lastConnection!!.sentMessages.size)
        session.closeAndJoin()
    }

    @Test fun inboundOverloadTerminatesGenerationAndResolvesPendingPublish() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory, SessionConfig(reconnectPolicy = ReconnectPolicy.never()))
        connect(session, factory)
        val handle = session.publish(TestFixtures.testEvent)
        runCurrent()
        repeat(2000) { factory.lastConnection!!.simulateMessage(TestFixtures.noticeJson("busy")) }
        runCurrent()
        assertIs<ConnectionStatus.Failed>(session.connectionStatus)
        assertEquals(PublishStatus.Abandoned, handle.status)
        assertEquals(TransportState.Closed, factory.lastConnection!!.state)
        session.closeAndJoin()
    }

    @Test fun backToBackDisconnectAndConnectStartsReplacementConnection() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = session(factory)
        connect(session, factory)
        session.disconnect()
        session.connect()
        runCurrent()
        assertEquals(2, factory.connections.size)
        assertEquals(TransportState.Closed, factory.connections.first().state)
        assertEquals(ConnectionStatus.Connecting, session.connectionStatus)
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        assertEquals(ConnectionStatus.Connected, session.connectionStatus)
        session.closeAndJoin()
    }

    @Test fun repeatedRelayClosuresRespectBoundedRetryPolicy() = runTest {
        val attempts = mutableListOf<Int>()
        val policy = object : RetryPolicy {
            override fun shouldRetryPublish(eventId: EventId, attempt: Int) = RetryDecision.Abandon
            override fun shouldResubscribe(
                subscriptionId: SubscriptionId, attempt: Int, reason: SubscriptionRetryReason,
            ): RetryDecision {
                attempts.add(attempt)
                return if (attempt <= 2) RetryDecision.Retry() else RetryDecision.Abandon
            }
        }
        val factory = FakeRelayTransportFactory()
        val session = session(factory, SessionConfig(retryPolicy = policy))
        connect(session, factory)
        val handle = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        runCurrent()
        repeat(3) {
            factory.lastConnection!!.simulateMessage(TestFixtures.closedJson(handle.subscriptionId, "temporarily unavailable"))
            runCurrent()
        }
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(3, factory.lastConnection!!.sentMessages.count { it.contains("REQ") })
        assertTrue(handle.status.isTerminal)
        session.closeAndJoin()
    }

    @Test fun terminalPooledPublishReleasesTrackingJobsWithoutManualClose() = runTest {
        val job = Job()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val handle = CoroutinesPublishHandle(TestFixtures.testEventId) {}
        val url = RelayUrl.parse("wss://test.relay")
        val pooled = CoroutinesPoolPublishHandle(TestFixtures.testEventId, mapOf(url to handle), scope)
        runCurrent()
        assertEquals(1, job.children.count())
        handle.updateStatus(PublishStatus.Accepted(""))
        runCurrent()
        assertEquals(0, job.children.count())
        assertIs<PublishStatus.Accepted>(pooled.status[url])
        assertIs<PublishStatus.Accepted>(pooled.awaitAll()[url])
        scope.cancel()
    }
}
