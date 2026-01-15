package io.github.nicolals.nostr.nip01.protocol

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.protocol.ProtocolSignal

/**
 * Signals emitted by the NIP-01 reducer.
 *
 * These signals notify consumers about subscription and publish
 * lifecycle events. The runtime surfaces these via flows or callbacks.
 */
sealed interface Nip01Signal : ProtocolSignal {

    // ========== Subscription Signals ==========

    /**
     * A subscription has been started (REQ sent).
     *
     * @property subscriptionId The subscription identifier.
     * @property generation The generation of this subscription instance.
     */
    data class SubscriptionStarted(
        val subscriptionId: SubscriptionId,
        val generation: Int,
    ) : Nip01Signal

    /**
     * End of stored events received for a subscription.
     *
     * After this signal, events are considered live/real-time.
     *
     * @property subscriptionId The subscription identifier.
     * @property generation The generation this EOSE applies to.
     */
    data class SubscriptionEose(
        val subscriptionId: SubscriptionId,
        val generation: Int,
    ) : Nip01Signal

    /**
     * An event was received for a subscription.
     *
     * The [generation] allows the runtime to route events to the
     * correct handle when subscriptions are replaced.
     *
     * @property subscriptionId The subscription identifier.
     * @property generation The generation this event belongs to.
     * @property event The received event.
     */
    data class SubscriptionEvent(
        val subscriptionId: SubscriptionId,
        val generation: Int,
        val event: Event,
    ) : Nip01Signal

    /**
     * A subscription was closed.
     *
     * @property subscriptionId The subscription identifier.
     * @property generation The generation that was closed.
     * @property reason The close reason from CLOSED message, if any.
     * @property byRelay True if closed by relay (CLOSED), false if by client (CLOSE).
     */
    data class SubscriptionClosed(
        val subscriptionId: SubscriptionId,
        val generation: Int,
        val reason: String?,
        val byRelay: Boolean,
    ) : Nip01Signal

    /**
     * A subscription was interrupted by disconnect.
     *
     * The subscription's final state is unknown. The runtime may
     * choose to resubscribe after reconnection.
     *
     * @property subscriptionId The subscription identifier.
     * @property generation The generation that was interrupted.
     */
    data class SubscriptionInterrupted(
        val subscriptionId: SubscriptionId,
        val generation: Int,
    ) : Nip01Signal

    // ========== Publish Signals ==========

    /**
     * A publish request has been queued (EVENT sent).
     *
     * @property eventId The event ID being published.
     */
    data class PublishQueued(val eventId: EventId) : Nip01Signal

    /**
     * A publish was accepted by the relay.
     *
     * @property eventId The event ID that was accepted.
     * @property message The message from OK response (may include "duplicate:" etc.).
     */
    data class PublishAccepted(
        val eventId: EventId,
        val message: String,
    ) : Nip01Signal

    /**
     * A publish was rejected by the relay.
     *
     * @property eventId The event ID that was rejected.
     * @property message The rejection reason from OK response.
     */
    data class PublishRejected(
        val eventId: EventId,
        val message: String,
    ) : Nip01Signal

    /**
     * A publish was interrupted by disconnect.
     *
     * The publish's final state is unknown - the relay may or may not
     * have received/processed the event. The runtime may choose to
     * retry after reconnection.
     *
     * @property eventId The event ID that was interrupted.
     */
    data class PublishInterrupted(val eventId: EventId) : Nip01Signal

    // ========== Other Signals ==========

    /**
     * A NOTICE message was received from the relay.
     *
     * NOTICE messages are informational and don't affect protocol state.
     *
     * @property message The notice message.
     */
    data class Notice(val message: String) : Nip01Signal
}
