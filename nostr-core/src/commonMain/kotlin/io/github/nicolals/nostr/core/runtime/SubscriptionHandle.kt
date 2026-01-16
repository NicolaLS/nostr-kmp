package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * Handle for tracking a subscription.
 *
 * Provides observable status of the subscription lifecycle:
 * Starting → CatchingUp → Live → Closing → Closed
 *
 * Subscriptions can also be Interrupted by disconnects or enter a
 * Retrying state when the runtime schedules a resubscribe.
 *
 * ## Usage
 *
 * ```kotlin
 * val handle = session.subscribe(filters)
 *
 * // Check current status
 * when (handle.status) {
 *     SubscriptionStatus.Live -> println("Receiving live events")
 *     SubscriptionStatus.CatchingUp -> println("Receiving stored events")
 *     // ...
 * }
 *
 * // Close when done
 * handle.close()
 * ```
 *
 * Runtime implementations provide methods for receiving events,
 * typically as a Flow or callback.
 */
interface SubscriptionHandle {
    /**
     * The subscription ID.
     */
    val subscriptionId: SubscriptionId

    /**
     * The current status of the subscription.
     */
    val status: SubscriptionStatus

    /**
     * Whether the subscription is currently active (receiving events).
     */
    val isActive: Boolean
        get() = status.isActive

    /**
     * Whether the subscription has reached a terminal state.
     */
    val isTerminal: Boolean
        get() = status.isTerminal

    /**
     * Requests to close this subscription.
     *
     * This sends a CLOSE message to the relay and transitions according
     * to the session's close policy (immediate close by default).
     *
     * This method is idempotent - calling it multiple times has
     * no additional effect.
     */
    fun close()
}

/**
 * Status of a subscription.
 */
sealed interface SubscriptionStatus {
    /**
     * Whether the subscription is currently active (can receive events).
     */
    val isActive: Boolean

    /**
     * Whether this is a terminal (final) status.
     */
    val isTerminal: Boolean

    /**
     * The subscription request is being sent.
     */
    data object Starting : SubscriptionStatus {
        override val isActive: Boolean = false
        override val isTerminal: Boolean = false
    }

    /**
     * Receiving stored/historical events.
     *
     * Events received in this phase are from the relay's database.
     * The subscription transitions to [Live] when EOSE is received.
     */
    data object CatchingUp : SubscriptionStatus {
        override val isActive: Boolean = true
        override val isTerminal: Boolean = false
    }

    /**
     * Receiving live/real-time events.
     *
     * EOSE has been received, so subsequent events are newly
     * published to the relay.
     */
    data object Live : SubscriptionStatus {
        override val isActive: Boolean = true
        override val isTerminal: Boolean = false
    }

    /**
     * Close has been requested, waiting for CLOSED.
     */
    data object Closing : SubscriptionStatus {
        override val isActive: Boolean = false
        override val isTerminal: Boolean = false
    }

    /**
     * The subscription is waiting to retry.
     *
     * @property reason The reason the retry is happening.
     * @property attempt The retry attempt number (1 for first retry).
     * @property delayMs Delay before the retry attempt.
     */
    data class Retrying(
        val reason: SubscriptionRetryReason,
        val attempt: Int,
        val delayMs: Long,
    ) : SubscriptionStatus {
        override val isActive: Boolean = false
        override val isTerminal: Boolean = false
    }

    /**
     * The subscription is closed.
     *
     * @property reason The close reason, if the relay sent one.
     * @property byRelay True if closed by the relay (CLOSED message),
     *   false if closed by the client (CLOSE message).
     */
    data class Closed(
        val reason: String? = null,
        val byRelay: Boolean = false,
    ) : SubscriptionStatus {
        override val isActive: Boolean = false
        override val isTerminal: Boolean = true
    }

    /**
     * The subscription was interrupted by a disconnect.
     *
     * The runtime may automatically resubscribe based on retry policy.
     */
    data object Interrupted : SubscriptionStatus {
        override val isActive: Boolean = false
        override val isTerminal: Boolean = false
    }
}
