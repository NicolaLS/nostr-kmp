package nostr.core.session

import nostr.core.crypto.Sha256
import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.RelayMessage
import nostr.core.model.SubscriptionId

/**
 * Configuration knobs for [DefaultRelaySessionReducer].
 *
 * @property maxEventReplayIds maximum number of event ids retained per subscription for duplicate suppression;
 * setting this to `0` disables the replay window entirely.
 * @property maxPublishStatuses maximum number of publish acknowledgements retained in [RelaySessionState.publishStatuses].
 * @property verifyEventIds when `true` the reducer recomputes the canonical event id for every relay `EVENT` frame and
 * rejects mismatches as protocol violations.
 * @property canonicalHasher hasher used for canonical-id verification; defaults to the shared [Sha256.Default].
 */
data class RelaySessionSettings(
    val maxEventReplayIds: Int = 200,
    val maxPublishStatuses: Int = 200,
    val verifyEventIds: Boolean = false,
    val canonicalHasher: Sha256 = Sha256.Default
)

/** Result of reducing an intent: a new [RelaySessionState] plus the commands to run. */
data class EngineTransition(
    val state: RelaySessionState,
    val commands: List<RelaySessionCommand>
)

/** Core reducer contract that turns intents into state transitions. */
fun interface RelaySessionReducer {
    fun reduce(state: RelaySessionState, intent: RelaySessionIntent): EngineTransition
}

/**
 * Default NIP-01 reducer used by the runtime and engine helpers. It implements duplicate
 * suppression, publish bookkeeping, and connection/subscription lifecycle management.
 */
class DefaultRelaySessionReducer(
    private val settings: RelaySessionSettings = RelaySessionSettings()
) : RelaySessionReducer {
    override fun reduce(state: RelaySessionState, intent: RelaySessionIntent): EngineTransition = when (intent) {
        is RelaySessionIntent.Connect -> handleConnect(state, intent)
        is RelaySessionIntent.Disconnect -> handleDisconnect(state, intent)
        is RelaySessionIntent.Subscribe -> handleSubscribe(state, intent)
        is RelaySessionIntent.Unsubscribe -> handleUnsubscribe(state, intent)
        is RelaySessionIntent.Publish -> handlePublish(state, intent)
        is RelaySessionIntent.ConnectionEstablished -> handleConnectionOpened(state, intent)
        is RelaySessionIntent.ConnectionClosed -> handleConnectionClosed(state, intent)
        is RelaySessionIntent.ConnectionFailed -> handleConnectionFailed(state, intent)
        is RelaySessionIntent.RelayEvent -> handleRelayMessage(state, intent.message)
        is RelaySessionIntent.OutboundFailure -> handleOutboundFailure(state, intent)
    }

    private fun handleConnect(state: RelaySessionState, intent: RelaySessionIntent.Connect): EngineTransition {
        if (state.connection is ConnectionSnapshot.Connected && state.connection.url == intent.url) {
            return EngineTransition(state, emptyList())
        }
        val commands = mutableListOf<RelaySessionCommand>()
        val nextState = state.copy(
            desiredRelayUrl = intent.url,
            connection = ConnectionSnapshot.Connecting(intent.url),
            lastError = null
        )
        commands += RelaySessionCommand.OpenConnection(intent.url)
        commands += RelaySessionCommand.EmitOutput(RelaySessionOutput.ConnectionStateChanged(nextState.connection))
        return EngineTransition(nextState, commands)
    }

    private fun handleDisconnect(state: RelaySessionState, intent: RelaySessionIntent.Disconnect): EngineTransition {
        val connection = state.connection
        if (connection !is ConnectionSnapshot.Connected) {
            val snapshot = ConnectionSnapshot.Disconnected
            val newState = state.copy(desiredRelayUrl = null, connection = snapshot)
            val commands = listOf(RelaySessionCommand.EmitOutput(RelaySessionOutput.ConnectionStateChanged(snapshot)))
            return EngineTransition(newState, commands)
        }
        val snapshot = ConnectionSnapshot.Disconnecting(connection.url, intent.code, intent.reason)
        val nextState = state.copy(
            desiredRelayUrl = null,
            connection = snapshot
        )
        val commands = listOf(
            RelaySessionCommand.CloseConnection(intent.code, intent.reason),
            RelaySessionCommand.EmitOutput(RelaySessionOutput.ConnectionStateChanged(snapshot))
        )
        return EngineTransition(nextState, commands)
    }

    private fun handleSubscribe(state: RelaySessionState, intent: RelaySessionIntent.Subscribe): EngineTransition {
        val existing = state.subscriptions[intent.subscriptionId]
        val status = when (state.connection) {
            is ConnectionSnapshot.Connected -> SubscriptionStatus.Active
            else -> SubscriptionStatus.Pending
        }
        val subscription = SubscriptionState(
            subscriptionId = intent.subscriptionId,
            filters = intent.filters,
            status = status,
            receivedEventIds = emptyList(),
            eoseReceived = false
        )
        val subscriptions: Map<SubscriptionId, SubscriptionState> =
            state.subscriptions + (intent.subscriptionId to subscription)
        val nextState = state.copy(subscriptions = subscriptions)
        val commands = mutableListOf<RelaySessionCommand>()
        commands += RelaySessionCommand.EmitOutput(RelaySessionOutput.SubscriptionRegistered(intent.subscriptionId))
        if (state.connection is ConnectionSnapshot.Connected) {
            commands += RelaySessionCommand.SendToRelay(
                ClientMessage.Req(intent.subscriptionId, intent.filters)
            )
        }
        return EngineTransition(nextState, commands)
    }

    private fun handleUnsubscribe(state: RelaySessionState, intent: RelaySessionIntent.Unsubscribe): EngineTransition {
        val subscription = state.subscriptions[intent.subscriptionId]
            ?: return EngineTransition(state, emptyList())
        val updatedSubscriptions: Map<SubscriptionId, SubscriptionState> =
            state.subscriptions + (intent.subscriptionId to subscription.copy(status = SubscriptionStatus.Closing))
        val nextState = state.copy(subscriptions = updatedSubscriptions)
        val commands = if (state.connection is ConnectionSnapshot.Connected) {
            listOf<RelaySessionCommand>(
                RelaySessionCommand.SendToRelay(ClientMessage.Close(intent.subscriptionId))
            )

        } else {
            emptyList()
        }
        return EngineTransition(nextState, commands)
    }

    private fun handlePublish(state: RelaySessionState, intent: RelaySessionIntent.Publish): EngineTransition {
        val commands = mutableListOf<RelaySessionCommand>()
        val nextPending: List<Event>
        val nextStatuses = state.publishStatuses.withLimitedStatus(intent.event.id, PublishStatus.Pending)
        if (state.connection is ConnectionSnapshot.Connected) {
            commands += RelaySessionCommand.SendToRelay(ClientMessage.Event(intent.event))
            nextPending = state.pendingPublishes
        } else {
            nextPending = state.pendingPublishes + intent.event
        }
        val nextState = state.copy(pendingPublishes = nextPending, publishStatuses = nextStatuses)
        return EngineTransition(nextState, commands)
    }

    private fun handleConnectionOpened(
        state: RelaySessionState,
        intent: RelaySessionIntent.ConnectionEstablished
    ): EngineTransition {
        val commands = mutableListOf<RelaySessionCommand>()
        val cleanedSubscriptions = mutableMapOf<SubscriptionId, SubscriptionState>()

        state.pendingPublishes.forEach { event ->
            commands += RelaySessionCommand.SendToRelay(ClientMessage.Event(event))
        }

        state.subscriptions.forEach { (id, subscription) ->
            when (subscription.status) {
                SubscriptionStatus.Closed -> cleanedSubscriptions[id] = subscription
                SubscriptionStatus.Closing -> {
                    cleanedSubscriptions[id] = subscription
                    commands += RelaySessionCommand.SendToRelay(ClientMessage.Close(id))
                }
                SubscriptionStatus.Pending, SubscriptionStatus.Active -> {
                    val refreshed = subscription.copy(
                        status = SubscriptionStatus.Active,
                        eoseReceived = false,
                        receivedEventIds = emptyList()
                    )
                    cleanedSubscriptions[id] = refreshed
                    commands += RelaySessionCommand.SendToRelay(
                        ClientMessage.Req(id, refreshed.filters)
                    )
                }
            }
        }

        val nextState = state.copy(
            connection = ConnectionSnapshot.Connected(intent.url),
            subscriptions = cleanedSubscriptions,
            pendingPublishes = emptyList(),
            lastError = null
        )
        commands += RelaySessionCommand.EmitOutput(RelaySessionOutput.ConnectionStateChanged(nextState.connection))
        return EngineTransition(nextState, commands)
    }

    private fun handleConnectionClosed(
        state: RelaySessionState,
        intent: RelaySessionIntent.ConnectionClosed
    ): EngineTransition {
        val resetSubscriptions: Map<SubscriptionId, SubscriptionState> =
            state.subscriptions.mapValues { (_, sub) ->
                if (sub.status == SubscriptionStatus.Closed) sub else sub.copy(status = SubscriptionStatus.Pending)
            }
        val nextState = state.copy(
            connection = ConnectionSnapshot.Disconnected,
            subscriptions = resetSubscriptions
        )
        val commands =
            listOf(RelaySessionCommand.EmitOutput(RelaySessionOutput.ConnectionStateChanged(nextState.connection)))
        return EngineTransition(nextState, commands)
    }

    private fun handleConnectionFailed(
        state: RelaySessionState,
        intent: RelaySessionIntent.ConnectionFailed
    ): EngineTransition {
        val error = EngineError.ConnectionFailure(state.desiredRelayUrl, intent.message)
        val nextState = state.copy(
            connection = ConnectionSnapshot.Failed(intent.url, intent.message),
            lastError = error
        )
        val commands = listOf(RelaySessionCommand.EmitOutput(RelaySessionOutput.Error(error)))
        return EngineTransition(nextState, commands)
    }

    private fun handleRelayMessage(state: RelaySessionState, message: RelayMessage): EngineTransition = when (message) {
        is RelayMessage.Event -> handleRelayEvent(state, message)
        is RelayMessage.Notice -> emitOnly(state, RelaySessionOutput.Notice(message.message))
        is RelayMessage.EndOfStoredEvents -> handleEose(state, message)
        is RelayMessage.Closed -> handleClosed(state, message)
        is RelayMessage.Ok -> handleOk(state, message)
        is RelayMessage.AuthChallenge -> emitOnly(state, RelaySessionOutput.AuthChallenge(message.challenge))
        is RelayMessage.Count -> emitOnly(state, RelaySessionOutput.CountResult(message.subscriptionId, message.count))
        is RelayMessage.Unknown -> handleUnknown(state, message)
    }

    private fun handleUnknown(state: RelaySessionState, message: RelayMessage.Unknown): EngineTransition {
        val reason = message.reason
        return emitOnly(state, RelaySessionOutput.Error(EngineError.ProtocolViolation(reason)))
    }

    private fun handleRelayEvent(state: RelaySessionState, message: RelayMessage.Event): EngineTransition {
        val subscription = state.subscriptions[message.subscriptionId]
            ?: return emitOnly(
                state,
                RelaySessionOutput.Error(EngineError.ProtocolViolation("Unknown subscription ${message.subscriptionId}"))
            )
        if (settings.verifyEventIds && !message.event.verifyId(settings.canonicalHasher)) {
            val error = EngineError.ProtocolViolation("Event ${message.event.id} has non-canonical id")
            return emitOnly(state, RelaySessionOutput.Error(error))
        }
        if (settings.maxEventReplayIds > 0 && subscription.receivedEventIds.contains(message.event.id)) {
            return EngineTransition(state, emptyList())
        }
        val updatedSubscription = subscription.copy(
            receivedEventIds = subscription.receivedEventIds.appendEventId(message.event.id),
            status = SubscriptionStatus.Active
        )
        val updatedSubscriptions: Map<SubscriptionId, SubscriptionState> =
            state.subscriptions + (subscription.subscriptionId to updatedSubscription)
        val nextState = state.copy(subscriptions = updatedSubscriptions)
        val commands = listOf(
            RelaySessionCommand.EmitOutput(
                RelaySessionOutput.EventReceived(
                    subscription.subscriptionId,
                    message.event
                )
            )
        )
        return EngineTransition(nextState, commands)
    }

    private fun handleEose(state: RelaySessionState, message: RelayMessage.EndOfStoredEvents): EngineTransition {
        val subscription = state.subscriptions[message.subscriptionId]
            ?: return emitOnly(
                state,
                RelaySessionOutput.Error(EngineError.ProtocolViolation("Unknown subscription ${message.subscriptionId}"))
            )
        val updated = subscription.copy(eoseReceived = true)
        val updatedSubscriptions: Map<SubscriptionId, SubscriptionState> =
            state.subscriptions + (subscription.subscriptionId to updated)
        val nextState = state.copy(subscriptions = updatedSubscriptions)
        val commands =
            listOf(RelaySessionCommand.EmitOutput(RelaySessionOutput.EndOfStoredEvents(subscription.subscriptionId)))
        return EngineTransition(nextState, commands)
    }

    private fun handleClosed(state: RelaySessionState, message: RelayMessage.Closed): EngineTransition {
        val subscription = state.subscriptions[message.subscriptionId]
            ?: return emitOnly(
                state,
                RelaySessionOutput.Notice("Relay closed unknown subscription ${message.subscriptionId}: ${message.reason}")
            )
        val updated = subscription.copy(status = SubscriptionStatus.Closed)
        val updatedSubscriptions: Map<SubscriptionId, SubscriptionState> =
            state.subscriptions + (subscription.subscriptionId to updated)
        val nextState = state.copy(subscriptions = updatedSubscriptions)
        val commands = listOf(
            RelaySessionCommand.EmitOutput(
                RelaySessionOutput.SubscriptionTerminated(
                    subscription.subscriptionId,
                    message.reason,
                    message.code
                )
            )
        )
        return EngineTransition(nextState, commands)
    }

    private fun handleOk(state: RelaySessionState, message: RelayMessage.Ok): EngineTransition {
        val nextState = state.copy(
            publishStatuses = state.publishStatuses.withLimitedStatus(
                message.result.eventId,
                PublishStatus.Acknowledged(message.result)
            )
        )
        val commands = listOf(RelaySessionCommand.EmitOutput(RelaySessionOutput.PublishAcknowledged(message.result)))
        return EngineTransition(nextState, commands)
    }

    private fun handleOutboundFailure(
        state: RelaySessionState,
        intent: RelaySessionIntent.OutboundFailure
    ): EngineTransition {
        val error = EngineError.OutboundFailure(intent.reason)
        val nextState = state.copy(lastError = error)
        val commands = listOf(RelaySessionCommand.EmitOutput(RelaySessionOutput.Error(error)))
        return EngineTransition(nextState, commands)
    }

    /**
     * Adds [eventId] to the end of the subscription history while enforcing the configured
     * replay window. The helper keeps the list ordered (oldest → newest), removes any previous
     * occurrence of the same id, and drops the oldest entries when the limit is exceeded.
     */
    private fun List<String>.appendEventId(eventId: String): List<String> {
        val limit = settings.maxEventReplayIds
        if (limit == 0) return emptyList()
        if (limit == 1) return listOf(eventId)
        val queue = ArrayDeque<String>(size + 1)
        for (existing in this) {
            if (existing != eventId) queue.addLast(existing)
        }
        queue.addLast(eventId)
        while (limit > 0 && queue.size > limit) {
            queue.removeFirst()
        }
        return queue.toList()
    }

    /**
     * Records the latest publish status for [eventId], retaining only the newest entries up to the
     * configured history size. Older acknowledgements fall off the front of the map so the engine
     * cannot grow without bound.
     */
    private fun Map<String, PublishStatus>.withLimitedStatus(
        eventId: String,
        status: PublishStatus
    ): Map<String, PublishStatus> {
        val limit = settings.maxPublishStatuses
        if (limit == 0) return emptyMap()
        val result = LinkedHashMap<String, PublishStatus>(this.size + 1)
        this.forEach { (key, value) ->
            if (key != eventId) result[key] = value
        }
        result[eventId] = status
        while (limit > 0 && result.size > limit) {
            val iterator = result.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return result
    }

    private fun emitOnly(state: RelaySessionState, output: RelaySessionOutput): EngineTransition =
        EngineTransition(state, listOf(RelaySessionCommand.EmitOutput(output)))
}
