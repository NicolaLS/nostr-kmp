package io.github.nicolals.nostr.nip01.protocol

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.protocol.ProtocolInput

/**
 * User intent inputs for NIP-01 operations.
 *
 * These inputs represent user/application requests to perform
 * protocol operations. The NIP-01 reducer processes these intents
 * and produces appropriate effects (sending messages) and state changes.
 */
sealed interface Nip01Intent : ProtocolInput {

    /**
     * Request to subscribe to events matching the given filters.
     *
     * ## Subscription Replacement
     *
     * If a subscription with the same [subscriptionId] already exists,
     * this replaces it. The generation counter increments, and events
     * from the old subscription are no longer delivered.
     *
     * @property subscriptionId The subscription identifier.
     * @property filters Filters defining which events to receive.
     */
    data class Subscribe(
        val subscriptionId: SubscriptionId,
        val filters: List<Filter>,
    ) : Nip01Intent

    /**
     * Request to publish an event to the relay.
     *
     * ## Republishing
     *
     * Publishing the same event (same event ID) multiple times is allowed.
     * Each publish sends an EVENT message. The state tracks the most recent
     * OK response.
     *
     * @property event The signed event to publish.
     */
    data class Publish(val event: Event) : Nip01Intent

    /**
     * Request to close a subscription.
     *
     * Sends a CLOSE message to the relay. The subscription transitions
     * to Closing state until CLOSED is received.
     *
     * @property subscriptionId The subscription to close.
     */
    data class CloseSubscription(val subscriptionId: SubscriptionId) : Nip01Intent

    /**
     * Remove subscription state from the engine.
     *
     * This is a cleanup operation for the runtime to call after a
     * subscription has reached a terminal state (Closed, Interrupted)
     * and is no longer needed.
     *
     * @property subscriptionId The subscription to forget.
     */
    data class ForgetSubscription(val subscriptionId: SubscriptionId) : Nip01Intent

    /**
     * Remove publish state from the engine.
     *
     * This is a cleanup operation for the runtime to call after a
     * publish has reached a terminal state (Accepted, Rejected, Interrupted)
     * and is no longer needed.
     *
     * Late OK messages for forgotten publishes are ignored.
     *
     * @property eventId The event ID to forget.
     */
    data class ForgetPublish(val eventId: EventId) : Nip01Intent
}
