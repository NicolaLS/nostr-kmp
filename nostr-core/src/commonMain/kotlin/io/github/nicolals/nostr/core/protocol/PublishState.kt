package io.github.nicolals.nostr.core.protocol

import io.github.nicolals.nostr.core.primitives.EventId

/**
 * The status of a publish operation.
 *
 * Publishes follow this state machine:
 * ```
 * Pending → Accepted
 *       ↘ Rejected
 *       ↘ Interrupted (on disconnect)
 * ```
 *
 * Note: Republishing an event (same event ID) is allowed and will
 * send another EVENT message, but the state remains based on the
 * most recent OK received.
 */
enum class PublishStatus {
    /**
     * EVENT has been sent; waiting for OK.
     *
     * The outcome is unknown until OK is received or the connection fails.
     */
    Pending,

    /**
     * OK received with `accepted = true`.
     *
     * The relay has accepted and stored the event. This is a terminal
     * state (sticky - does not regress on republish).
     */
    Accepted,

    /**
     * OK received with `accepted = false`.
     *
     * The relay rejected the event. The [PublishState.okMessage] contains
     * the reason (may include prefixes like "duplicate:", "rate-limited:").
     */
    Rejected,

    /**
     * The connection was lost while the publish was pending.
     *
     * The outcome is unknown - the event may or may not have been received
     * by the relay. The runtime may choose to retry on reconnection.
     */
    Interrupted,
}

/**
 * State of a single publish operation.
 *
 * ## Event ID Correlation
 *
 * Publish state is keyed by event ID. When an OK message is received,
 * it's correlated to the publish state by the event ID in the OK.
 *
 * ## OK Message Preservation
 *
 * The [okMessage] preserves the full message from the relay's OK response,
 * including any machine-readable prefix (e.g., "duplicate:", "rate-limited:",
 * "blocked:", "invalid:"). This allows consumers to make policy decisions
 * based on the specific reason.
 *
 * @property eventId The ID of the event being published.
 * @property status Current publish status.
 * @property okMessage The message from the OK response, if received.
 */
data class PublishState(
    val eventId: EventId,
    val status: PublishStatus,
    val okMessage: String? = null,
) {
    /**
     * Whether this publish is still waiting for a response.
     */
    val isPending: Boolean
        get() = status == PublishStatus.Pending

    /**
     * Whether this publish has reached a terminal state.
     */
    val isTerminal: Boolean
        get() = status == PublishStatus.Accepted ||
            status == PublishStatus.Rejected ||
            status == PublishStatus.Interrupted
}
