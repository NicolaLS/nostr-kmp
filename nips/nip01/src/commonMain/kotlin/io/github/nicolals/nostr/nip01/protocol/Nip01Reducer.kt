package io.github.nicolals.nostr.nip01.protocol

import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.protocol.ConnectionPhase
import io.github.nicolals.nostr.core.protocol.ConnectionState
import io.github.nicolals.nostr.core.protocol.CoreSignal
import io.github.nicolals.nostr.core.protocol.InboundMessage
import io.github.nicolals.nostr.core.protocol.ProtocolEffect
import io.github.nicolals.nostr.core.protocol.ProtocolInput
import io.github.nicolals.nostr.core.protocol.ProtocolReducer
import io.github.nicolals.nostr.core.protocol.ProtocolSignal
import io.github.nicolals.nostr.core.protocol.ProtocolState
import io.github.nicolals.nostr.core.protocol.PublishState
import io.github.nicolals.nostr.core.protocol.PublishStatus
import io.github.nicolals.nostr.core.protocol.ReduceResult
import io.github.nicolals.nostr.core.protocol.SubscriptionPhase
import io.github.nicolals.nostr.core.protocol.SubscriptionState
import io.github.nicolals.nostr.core.protocol.TransportInput
import io.github.nicolals.nostr.nip01.message.CloseClientMessage
import io.github.nicolals.nostr.nip01.message.ClosedMessage
import io.github.nicolals.nostr.nip01.message.EoseMessage
import io.github.nicolals.nostr.nip01.message.EventClientMessage
import io.github.nicolals.nostr.nip01.message.NoticeMessage
import io.github.nicolals.nostr.nip01.message.OkMessage
import io.github.nicolals.nostr.nip01.message.RelayEventMessage
import io.github.nicolals.nostr.nip01.message.ReqClientMessage

/**
 * NIP-01 protocol reducer implementing subscription and publish semantics.
 *
 * This reducer handles:
 * - Transport lifecycle (connection opened/closed/failed)
 * - Subscription lifecycle (REQ → EOSE → CLOSE/CLOSED)
 * - Publish lifecycle (EVENT → OK)
 * - Event routing with generation tracking
 * - NOTICE messages
 *
 * ## Protocol Semantics
 *
 * The reducer encodes NIP-01 protocol facts, not policy decisions:
 * - REQ with same subscription ID replaces the previous subscription
 * - EOSE transitions subscription from CatchingUp to Live
 * - Events are only delivered to active subscriptions with matching generation
 * - Disconnect marks pending operations as Interrupted (not failed)
 * - OK messages are correlated to publishes by event ID
 *
 * @param wireCodec Codec for creating client messages.
 */
class Nip01Reducer(
    private val wireCodec: NostrWireCodec,
) : ProtocolReducer {

    override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
        return when (input) {
            // Transport lifecycle
            is TransportInput.Opened -> handleTransportOpened(state)
            is TransportInput.Closed -> handleTransportClosed(state, input)
            is TransportInput.Failed -> handleTransportFailed(state, input)

            // User intents
            is Nip01Intent.Subscribe -> handleSubscribe(state, input)
            is Nip01Intent.Publish -> handlePublish(state, input)
            is Nip01Intent.CloseSubscription -> handleCloseSubscription(state, input)
            is Nip01Intent.ForgetSubscription -> handleForgetSubscription(state, input)
            is Nip01Intent.ForgetPublish -> handleForgetPublish(state, input)

            // Inbound messages
            is InboundMessage -> handleInboundMessage(state, input.message)

            else -> null
        }
    }

    // ========== Transport Lifecycle ==========

    private fun handleTransportOpened(state: ProtocolState): ReduceResult {
        val newState = state.withConnection(ConnectionState(ConnectionPhase.Open))
        return ReduceResult.withSignals(newState, CoreSignal.ConnectionOpened)
    }

    private fun handleTransportClosed(
        state: ProtocolState,
        input: TransportInput.Closed,
    ): ReduceResult {
        val (newState, signals) = interruptActiveOperations(state)
        val finalState = newState.withConnection(ConnectionState(ConnectionPhase.Disconnected))
        return ReduceResult(
            state = finalState,
            signals = signals + CoreSignal.ConnectionClosed(input.info),
        )
    }

    private fun handleTransportFailed(
        state: ProtocolState,
        input: TransportInput.Failed,
    ): ReduceResult {
        val (newState, signals) = interruptActiveOperations(state)
        val finalState = newState.withConnection(ConnectionState(ConnectionPhase.Disconnected))
        return ReduceResult(
            state = finalState,
            signals = signals + CoreSignal.ConnectionFailed(input.error),
        )
    }

    /**
     * Marks all active subscriptions and pending publishes as Interrupted.
     */
    private fun interruptActiveOperations(
        state: ProtocolState,
    ): Pair<ProtocolState, List<ProtocolSignal>> {
        var newState = state
        val signals = mutableListOf<ProtocolSignal>()

        // Interrupt active subscriptions
        for ((_, sub) in state.subscriptions) {
            if (sub.isActive) {
                val interrupted = sub.copy(phase = SubscriptionPhase.Interrupted)
                newState = newState.withSubscription(interrupted)
                signals += Nip01Signal.SubscriptionInterrupted(sub.subscriptionId, sub.generation)
            }
        }

        // Interrupt pending publishes
        for ((_, pub) in state.publishes) {
            if (pub.isPending) {
                val interrupted = pub.copy(status = PublishStatus.Interrupted)
                newState = newState.withPublish(interrupted)
                signals += Nip01Signal.PublishInterrupted(pub.eventId)
            }
        }

        return newState to signals
    }

    // ========== User Intents ==========

    private fun handleSubscribe(
        state: ProtocolState,
        intent: Nip01Intent.Subscribe,
    ): ReduceResult {
        if (!state.isConnected) {
            // Cannot subscribe when not connected - no state change
            return ReduceResult.stateOnly(state)
        }

        val existing = state.subscription(intent.subscriptionId)
        val generation = (existing?.generation ?: 0) + 1

        val subState = SubscriptionState(
            subscriptionId = intent.subscriptionId,
            generation = generation,
            phase = SubscriptionPhase.CatchingUp,
            filters = intent.filters,
        )

        val reqMessage = ReqClientMessage.create(
            intent.subscriptionId,
            intent.filters,
            wireCodec,
        )

        val newState = state.withSubscription(subState)

        return ReduceResult(
            state = newState,
            effects = listOf(ProtocolEffect.Send(reqMessage)),
            signals = listOf(Nip01Signal.SubscriptionStarted(intent.subscriptionId, generation)),
        )
    }

    private fun handlePublish(
        state: ProtocolState,
        intent: Nip01Intent.Publish,
    ): ReduceResult {
        if (!state.isConnected) {
            // Cannot publish when not connected - no state change
            return ReduceResult.stateOnly(state)
        }

        val eventId = intent.event.id
        val existing = state.publish(eventId)

        // If already accepted, keep accepted status (sticky) but still send
        val newStatus = if (existing?.status == PublishStatus.Accepted) {
            PublishStatus.Accepted
        } else {
            PublishStatus.Pending
        }

        val pubState = PublishState(
            eventId = eventId,
            status = newStatus,
            okMessage = existing?.okMessage,
        )

        val eventMessage = EventClientMessage.create(intent.event, wireCodec)
        val newState = state.withPublish(pubState)

        val signals = if (newStatus == PublishStatus.Pending) {
            listOf(Nip01Signal.PublishQueued(eventId))
        } else {
            emptyList()
        }

        return ReduceResult(
            state = newState,
            effects = listOf(ProtocolEffect.Send(eventMessage)),
            signals = signals,
        )
    }

    private fun handleCloseSubscription(
        state: ProtocolState,
        intent: Nip01Intent.CloseSubscription,
    ): ReduceResult {
        val sub = state.subscription(intent.subscriptionId)
            ?: return ReduceResult.stateOnly(state)

        // Don't close if already closing, closed, or interrupted
        if (sub.isTerminal || sub.phase == SubscriptionPhase.Closing) {
            return ReduceResult.stateOnly(state)
        }

        val closingState = sub.copy(phase = SubscriptionPhase.Closing)
        val closeMessage = CloseClientMessage.create(intent.subscriptionId)
        val newState = state.withSubscription(closingState)

        return ReduceResult(
            state = newState,
            effects = listOf(ProtocolEffect.Send(closeMessage)),
        )
    }

    private fun handleForgetSubscription(
        state: ProtocolState,
        intent: Nip01Intent.ForgetSubscription,
    ): ReduceResult {
        val newState = state.withoutSubscription(intent.subscriptionId)
        return ReduceResult.stateOnly(newState)
    }

    private fun handleForgetPublish(
        state: ProtocolState,
        intent: Nip01Intent.ForgetPublish,
    ): ReduceResult {
        val newState = state.withoutPublish(intent.eventId)
        return ReduceResult.stateOnly(newState)
    }

    // ========== Inbound Messages ==========

    private fun handleInboundMessage(
        state: ProtocolState,
        message: io.github.nicolals.nostr.core.message.view.RelayMessageView,
    ): ReduceResult? {
        return when (message) {
            is RelayEventMessage -> handleRelayEvent(state, message)
            is EoseMessage -> handleEose(state, message)
            is OkMessage -> handleOk(state, message)
            is ClosedMessage -> handleClosed(state, message)
            is NoticeMessage -> handleNotice(state, message)
            else -> null
        }
    }

    private fun handleRelayEvent(
        state: ProtocolState,
        message: RelayEventMessage,
    ): ReduceResult {
        val subscriptionId = message.subscriptionId
            ?: return ReduceResult.stateOnly(state)

        val event = message.event
            ?: return ReduceResult.stateOnly(state)

        val sub = state.subscription(subscriptionId)
            ?: return ReduceResult.stateOnly(state)

        // Only deliver to active subscriptions
        if (!sub.isActive) {
            return ReduceResult.stateOnly(state)
        }

        val signal = Nip01Signal.SubscriptionEvent(
            subscriptionId = subscriptionId,
            generation = sub.generation,
            event = event,
        )

        return ReduceResult.withSignals(state, signal)
    }

    private fun handleEose(
        state: ProtocolState,
        message: EoseMessage,
    ): ReduceResult {
        val subscriptionId = message.subscriptionId
            ?: return ReduceResult.stateOnly(state)

        val sub = state.subscription(subscriptionId)
            ?: return ReduceResult.stateOnly(state)

        // EOSE only transitions CatchingUp → Live
        if (sub.phase != SubscriptionPhase.CatchingUp) {
            return ReduceResult.stateOnly(state)
        }

        val liveState = sub.copy(phase = SubscriptionPhase.Live)
        val newState = state.withSubscription(liveState)

        val signal = Nip01Signal.SubscriptionEose(
            subscriptionId = subscriptionId,
            generation = sub.generation,
        )

        return ReduceResult.withSignals(newState, signal)
    }

    private fun handleOk(
        state: ProtocolState,
        message: OkMessage,
    ): ReduceResult {
        val eventId = message.eventId
            ?: return ReduceResult.stateOnly(state)

        val pub = state.publish(eventId)

        // If we don't know about this publish, ignore (late OK after forget)
        if (pub == null) {
            return ReduceResult.stateOnly(state)
        }

        // If already accepted, don't regress (sticky)
        if (pub.status == PublishStatus.Accepted) {
            return ReduceResult.stateOnly(state)
        }

        val newStatus = if (message.accepted) {
            PublishStatus.Accepted
        } else {
            PublishStatus.Rejected
        }

        val newPubState = pub.copy(
            status = newStatus,
            okMessage = message.message,
        )

        val newState = state.withPublish(newPubState)

        val signal = if (message.accepted) {
            Nip01Signal.PublishAccepted(eventId, message.message)
        } else {
            Nip01Signal.PublishRejected(eventId, message.message)
        }

        return ReduceResult.withSignals(newState, signal)
    }

    private fun handleClosed(
        state: ProtocolState,
        message: ClosedMessage,
    ): ReduceResult {
        val subscriptionId = message.subscriptionId
            ?: return ReduceResult.stateOnly(state)

        val sub = state.subscription(subscriptionId)
            ?: return ReduceResult.stateOnly(state)

        // Don't transition if already terminal
        if (sub.isTerminal) {
            return ReduceResult.stateOnly(state)
        }

        val closedState = sub.copy(
            phase = SubscriptionPhase.Closed,
            closedReason = message.message,
        )

        val newState = state.withSubscription(closedState)

        val signal = Nip01Signal.SubscriptionClosed(
            subscriptionId = subscriptionId,
            generation = sub.generation,
            reason = message.message,
            byRelay = true,
        )

        return ReduceResult.withSignals(newState, signal)
    }

    private fun handleNotice(
        state: ProtocolState,
        message: NoticeMessage,
    ): ReduceResult {
        return ReduceResult.withSignals(state, Nip01Signal.Notice(message.message))
    }
}
