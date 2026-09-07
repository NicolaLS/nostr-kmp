package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.runtime.SessionConfig
import io.github.nicolals.nostr.core.runtime.SubscriptionOverflowPolicy
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionOverflowTest {
    @Test fun defaultOverflowFailsIncompleteSubscriptionEvenWithoutErrorObserver() = runTest {
        val (session, factory, handle) = overflowingSubscription(SubscriptionOverflowPolicy.Fail)
        assertIs<SubscriptionStatus.Closed>(handle.status)
        assertEquals(1, handle.droppedEventsFlow.value)
        val received = mutableListOf<Event>()
        var failure: Throwable? = null
        handle.events.catch { failure = it }.collect { received += it }
        assertEquals(2, received.size)
        assertIs<SubscriptionBufferOverflowException>(failure)
        factory.lastConnection!!.simulateMessage(TestFixtures.eoseJson(handle.subscriptionId))
        runCurrent()
        assertIs<SubscriptionStatus.Closed>(handle.status)
        assertTrue(factory.lastConnection!!.sentMessages.any { it.startsWith("[\"CLOSE\"") })
        assertFalse(session.isClosed)
        session.closeAndJoin()
    }

    @Test fun explicitDropPolicyAllowsReplayOfDroppedEvent() = runTest {
        val (session, factory, handle) = overflowingSubscription(SubscriptionOverflowPolicy.DropNewest)
        val received = mutableListOf<Event>()
        launch { handle.events.collect { received += it } }
        runCurrent()
        assertEquals(2, received.size)
        factory.lastConnection!!.simulateMessage(TestFixtures.eventJson(handle.subscriptionId, event(3)))
        runCurrent()
        factory.lastConnection!!.simulateMessage(TestFixtures.eventJson(handle.subscriptionId, event(3)))
        factory.lastConnection!!.simulateMessage(TestFixtures.eoseJson(handle.subscriptionId))
        runCurrent()
        assertEquals(listOf(event(1), event(2), event(3)), received)
        assertEquals(1, handle.droppedEventsFlow.value)
        assertEquals(SubscriptionStatus.Live, handle.status)
        session.closeAndJoin()
    }

    private fun event(n: Int) = TestFixtures.testEvent.copy(id = EventId.fromHex(n.toString(16).padStart(64, '0')))

    private fun TestScope.overflowingSubscription(policy: SubscriptionOverflowPolicy):
        Triple<CoroutinesRelaySession, FakeRelayTransportFactory, CoroutinesSubscriptionHandle> {
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), TestWireCodec(), AcceptTestEvents, factory, this,
            SessionConfig(subscriptionBufferCapacity = 2, subscriptionOverflowPolicy = policy),
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val handle = session.subscribe(TestFixtures.testFilters)
        runCurrent()
        for (n in 1..3) {
            factory.lastConnection!!.simulateMessage(TestFixtures.eventJson(handle.subscriptionId, event(n)))
            runCurrent()
        }
        return Triple(session, factory, handle)
    }
}
