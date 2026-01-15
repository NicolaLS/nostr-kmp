package io.github.nicolals.nostr.core.protocol

import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * The phase of a subscription lifecycle.
 *
 * Subscriptions follow this state machine:
 * ```
 * CatchingUp → Live → Closing → Closed
 *          ↘      ↘       ↘
 *           Interrupted (on disconnect)
 * ```
 *
 * Note: CLOSED can arrive immediately after REQ (relay rejection),
 * bypassing Live state entirely.
 */
enum class SubscriptionPhase {
    /**
     * REQ has been sent; waiting for EOSE (end of stored events).
     *
     * Events received in this phase are historical/stored events.
     */
    CatchingUp,

    /**
     * EOSE received; now receiving live events.
     *
     * Events received in this phase are real-time.
     */
    Live,

    /**
     * CLOSE has been sent; waiting for relay confirmation.
     *
     * The subscription may still receive events until CLOSED is received.
     */
    Closing,

    /**
     * Subscription has ended (CLOSED received or confirmed).
     *
     * This is a terminal state. No more events will be delivered.
     */
    Closed,

    /**
     * The connection was lost while the subscription was active.
     *
     * This is a terminal state. The runtime may choose to resubscribe
     * after reconnection, but that would be a new subscription.
     */
    Interrupted,
}

/**
 * State of a single subscription.
 *
 * ## Generation Tracking
 *
 * The [generation] counter handles REQ replacement semantics correctly.
 * When a new REQ is sent with the same subscription ID, the generation
 * increments. Events and EOSE messages are only delivered if they match
 * the current generation, preventing stale messages from the replaced
 * subscription from being delivered.
 *
 * @property subscriptionId The subscription identifier.
 * @property generation Incremented on each REQ for this subscription ID.
 * @property phase Current lifecycle phase.
 * @property filters The filters for this subscription.
 * @property closedReason The reason provided by CLOSED message, if any.
 */
data class SubscriptionState(
    val subscriptionId: SubscriptionId,
    val generation: Int,
    val phase: SubscriptionPhase,
    val filters: List<Filter>,
    val closedReason: String? = null,
) {
    /**
     * Whether this subscription can receive events.
     *
     * Events are only delivered in CatchingUp or Live phases.
     */
    val isActive: Boolean
        get() = phase == SubscriptionPhase.CatchingUp || phase == SubscriptionPhase.Live

    /**
     * Whether this subscription has reached a terminal state.
     */
    val isTerminal: Boolean
        get() = phase == SubscriptionPhase.Closed || phase == SubscriptionPhase.Interrupted
}
