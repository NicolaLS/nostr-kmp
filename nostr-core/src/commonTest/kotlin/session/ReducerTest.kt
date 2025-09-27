package nostr.core.engine

import nostr.core.model.*
import nostr.core.session.*
import kotlin.test.Test
import kotlin.test.*

class ReducerTest {

    @Test
    fun connectIntentRequestsOpenConnection() {
        val engine = RelaySessionEngine(reducer = DefaultRelaySessionReducer())
        val transition = engine.dispatch(RelaySessionIntent.Connect("wss://relay.example"))

        assertIs<ConnectionSnapshot.Connecting>(transition.state.connection)
        assertCommandExists<RelaySessionCommand.OpenConnection>(transition.commands) { cmd ->
            assertEquals("wss://relay.example", cmd.url)
        }
        assertCommandExists<RelaySessionCommand.EmitOutput>(transition.commands) { cmd ->
            val output = assertIs<RelaySessionOutput.ConnectionStateChanged>(cmd.output)
            assertIs<ConnectionSnapshot.Connecting>(output.snapshot)
        }
    }

    @Test
    fun subscriptionSendsReqOnceConnected() {
        val filters = listOf(Filter(kinds = setOf(1)))
        val engine = RelaySessionEngine(reducer = DefaultRelaySessionReducer())
        engine.dispatch(RelaySessionIntent.Connect("wss://relay"))
        val subId = SubscriptionId("sub-id")
        val subTransition = engine.dispatch(RelaySessionIntent.Subscribe(subId, filters))
        assertTrue(subTransition.commands.none { it is RelaySessionCommand.SendToRelay })

        val connectTransition = engine.dispatch(RelaySessionIntent.ConnectionEstablished("wss://relay"))
        val send = assertCommandExists<RelaySessionCommand.SendToRelay>(connectTransition.commands) { it }
        val message = send.message
        val req = assertIs<ClientMessage.Req>(message)
        assertEquals(subId, req.subscriptionId)
        assertEquals(filters, req.filters)

        val subscriptionState = connectTransition.state.subscriptions.getValue(subId)
        assertEquals(SubscriptionStatus.Active, subscriptionState.status)
    }

    @Test
    fun eventMessageEmitsOutputAndDedupe() {
        val event = sampleEvent()
        val state = RelaySessionState(
            connection = ConnectionSnapshot.Connected("wss://relay"),
            subscriptions = mapOf(
                SubscriptionId("sub") to SubscriptionState(
                    subscriptionId = SubscriptionId("sub"),
                    filters = emptyList(),
                    status = SubscriptionStatus.Active
                )
            )
        )
        val reducer = DefaultRelaySessionReducer()

        val first = reducer.reduce(state, RelaySessionIntent.RelayEvent(RelayMessage.Event(SubscriptionId("sub"), event)))
        val emit = assertCommandExists<RelaySessionCommand.EmitOutput>(first.commands) { it }
        assertIs<RelaySessionOutput.EventReceived>(emit.output)

        val second = reducer.reduce(first.state, RelaySessionIntent.RelayEvent(RelayMessage.Event(SubscriptionId("sub"), event)))
        assertTrue(second.commands.isEmpty())
    }

    @Test
    fun tamperedEventRejectedWhenVerificationEnabled() {
        val filters = emptyList<Filter>()
        val subscriptionId = SubscriptionId("sub")
        val state = RelaySessionState(
            connection = ConnectionSnapshot.Connected("wss://relay"),
            subscriptions = mapOf(
                subscriptionId to SubscriptionState(
                    subscriptionId = subscriptionId,
                    filters = filters,
                    status = SubscriptionStatus.Active
                )
            )
        )
        val reducer = DefaultRelaySessionReducer(
            RelaySessionSettings(verifyEventIds = true)
        )

        val tampered = sampleEvent().copy(id = "f".repeat(64))

        val transition = reducer.reduce(
            state,
            RelaySessionIntent.RelayEvent(RelayMessage.Event(subscriptionId, tampered))
        )

        val output = assertCommandExists<RelaySessionCommand.EmitOutput>(transition.commands) { it }
        val error = assertIs<RelaySessionOutput.Error>(output.output)
        assertTrue(error.error is EngineError.ProtocolViolation)
    }

    @Test
    fun closedOutputIncludesCodeAndReason() {
        val subscriptionId = SubscriptionId("sub")
        val state = RelaySessionState(
            connection = ConnectionSnapshot.Connected("wss://relay"),
            subscriptions = mapOf(
                subscriptionId to SubscriptionState(
                    subscriptionId = subscriptionId,
                    filters = emptyList(),
                    status = SubscriptionStatus.Active
                )
            )
        )
        val reducer = DefaultRelaySessionReducer()

        val closed = RelayMessage.Closed(subscriptionId, reason = "banned", code = "blocked")
        val transition = reducer.reduce(state, RelaySessionIntent.RelayEvent(closed))

        val output = assertCommandExists<RelaySessionCommand.EmitOutput>(transition.commands) { it }
        val terminated = assertIs<RelaySessionOutput.SubscriptionTerminated>(output.output)
        assertEquals("blocked", terminated.code)
        assertEquals("banned", terminated.reason)
    }

    @Test
    fun publishQueuesWhileDisconnectedAndFlushesOnConnect() {
        val engine = RelaySessionEngine(reducer = DefaultRelaySessionReducer())
        val event = sampleEvent(idSeed = "99")

        val publishTransition = engine.dispatch(RelaySessionIntent.Publish(event))
        assertTrue(publishTransition.commands.isEmpty())
        assertEquals(listOf(event), publishTransition.state.pendingPublishes)

        val connectTransition = engine.dispatch(RelaySessionIntent.ConnectionEstablished("wss://relay"))
        val send = assertCommandExists<RelaySessionCommand.SendToRelay>(connectTransition.commands) { it }
        assertIs<ClientMessage.Event>(send.message)
        assertTrue(connectTransition.state.pendingPublishes.isEmpty())
    }

    @Test
    fun receivedEventHistoryIsBounded() {
        val settings = RelaySessionSettings(maxEventReplayIds = 3)
        val reducer = DefaultRelaySessionReducer(settings)
        val baseSubscription = SubscriptionState(
            subscriptionId = SubscriptionId("sub"),
            filters = emptyList(),
            status = SubscriptionStatus.Active
        )
        var state = RelaySessionState(
            connection = ConnectionSnapshot.Connected("wss://relay"),
            subscriptions = mapOf(SubscriptionId("sub") to baseSubscription)
        )

        val events = buildList {
            repeat(settings.maxEventReplayIds + 5) { idx ->
                val seed = idx.toString(16).padStart(2, '0')
                add(sampleEvent(idSeed = seed))
            }
        }

        events.forEach { event ->
            state = reducer.reduce(
                state,
                RelaySessionIntent.RelayEvent(RelayMessage.Event(SubscriptionId("sub"), event))
            ).state
        }

        val updated = state.subscriptions.getValue(SubscriptionId("sub"))
        assertEquals(settings.maxEventReplayIds, updated.receivedEventIds.size)
        assertEquals(events.takeLast(settings.maxEventReplayIds).map { it.id }, updated.receivedEventIds)

        val dedupe = reducer.reduce(
            state,
            RelaySessionIntent.RelayEvent(RelayMessage.Event(SubscriptionId("sub"), events.last()))
        )
        assertTrue(dedupe.commands.isEmpty())
    }

    @Test
    fun publishStatusHistoryIsBounded() {
        val settings = RelaySessionSettings(maxPublishStatuses = 3)
        val reducer = DefaultRelaySessionReducer(settings)
        var state = RelaySessionState()
        val events = buildList {
            repeat(settings.maxPublishStatuses + 5) { idx ->
                val seed = (idx + 1).toString(16).padStart(2, '0')
                val event = sampleEvent(idSeed = seed)
                state = reducer.reduce(state, RelaySessionIntent.Publish(event)).state
                add(event)
            }
        }

        assertEquals(settings.maxPublishStatuses, state.publishStatuses.size)
        assertTrue(state.publishStatuses.keys.containsAll(events.takeLast(settings.maxPublishStatuses).map { it.id }))

        val ackResult = PublishResult(events.last().id, accepted = true, message = "")
        state = reducer.reduce(
            state,
            RelaySessionIntent.RelayEvent(RelayMessage.Ok(ackResult))
        ).state
        assertTrue(state.publishStatuses.size <= settings.maxPublishStatuses)
        assertEquals(PublishStatus.Acknowledged(ackResult), state.publishStatuses[events.last().id])
    }

    @Test
    fun replacingSubscriptionClearsDedupeHistory() {
        val filters = listOf(Filter(kinds = setOf(1)))
        val subscriptionId = SubscriptionId("sub")
        val existing = SubscriptionState(
            subscriptionId = subscriptionId,
            filters = filters,
            status = SubscriptionStatus.Active,
            receivedEventIds = listOf("abc"),
            eoseReceived = true
        )
        val state = RelaySessionState(
            connection = ConnectionSnapshot.Connected("wss://relay"),
            subscriptions = mapOf(subscriptionId to existing)
        )
        val reducer = DefaultRelaySessionReducer()

        val transition = reducer.reduce(state, RelaySessionIntent.Subscribe(subscriptionId, filters))
        val updated = transition.state.subscriptions.getValue(subscriptionId)

        assertTrue(updated.receivedEventIds.isEmpty())
        assertFalse(updated.eoseReceived)

        val sendReq = assertCommandExists<RelaySessionCommand.SendToRelay>(transition.commands) { it }
        assertIs<ClientMessage.Req>(sendReq.message)
    }

    @Test
    fun connectionReestablishClearsDedupeHistory() {
        val filters = listOf(Filter(kinds = setOf(1)))
        val subscriptionId = SubscriptionId("sub")
        val subscription = SubscriptionState(
            subscriptionId = subscriptionId,
            filters = filters,
            status = SubscriptionStatus.Pending,
            receivedEventIds = listOf("abc", "def"),
            eoseReceived = true
        )
        val state = RelaySessionState(
            connection = ConnectionSnapshot.Connecting("wss://relay"),
            subscriptions = mapOf(subscriptionId to subscription)
        )
        val reducer = DefaultRelaySessionReducer()

        val transition = reducer.reduce(state, RelaySessionIntent.ConnectionEstablished("wss://relay"))
        val updated = transition.state.subscriptions.getValue(subscriptionId)

        assertEquals(SubscriptionStatus.Active, updated.status)
        assertTrue(updated.receivedEventIds.isEmpty())
        assertFalse(updated.eoseReceived)

        val sendReq = assertCommandExists<RelaySessionCommand.SendToRelay>(transition.commands) { it }
        val req = assertIs<ClientMessage.Req>(sendReq.message)
        assertEquals(subscriptionId, req.subscriptionId)
    }

    @Test
    fun closingSubscriptionsSendCloseOnReconnect() {
        val subscriptionId = SubscriptionId("sub")
        val closing = SubscriptionState(
            subscriptionId = subscriptionId,
            filters = emptyList(),
            status = SubscriptionStatus.Closing,
            receivedEventIds = listOf("abc"),
            eoseReceived = true
        )
        val state = RelaySessionState(
            connection = ConnectionSnapshot.Disconnected,
            subscriptions = mapOf(subscriptionId to closing)
        )
        val reducer = DefaultRelaySessionReducer()

        val transition = reducer.reduce(state, RelaySessionIntent.ConnectionEstablished("wss://relay"))

        val close = assertCommandExists<RelaySessionCommand.SendToRelay>(transition.commands) { it }
        val message = assertIs<ClientMessage.Close>(close.message)
        assertEquals(subscriptionId, message.subscriptionId)

        val refreshed = transition.state.subscriptions.getValue(subscriptionId)
        assertEquals(SubscriptionStatus.Closing, refreshed.status)
        assertEquals(listOf("abc"), refreshed.receivedEventIds)
    }

    private inline fun <reified T : RelaySessionCommand> assertCommandExists(
        commands: List<RelaySessionCommand>,
        block: (T) -> Unit
    ): T {
        val command = commands.filterIsInstance<T>().firstOrNull()
            ?: error("Expected command ${T::class.simpleName}")
        block(command)
        return command
    }

    private fun sampleEvent(idSeed: String = "01"): Event = Event(
        id = idSeed.repeat(32),
        pubkey = "a".repeat(64),
        createdAt = 1_700_000_100,
        kind = 1,
        tags = listOf(listOf("p", "peer")),
        content = "content",
        sig = "b".repeat(128)
    )
}
