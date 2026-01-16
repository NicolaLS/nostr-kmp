package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.primitives.EventId

/**
 * Handle for tracking a publish operation.
 *
 * Provides observable status of the publish lifecycle:
 * Queued → Pending → Accepted/Rejected/Interrupted/Abandoned/Replaced
 *
 * ## Usage
 *
 * ```kotlin
 * val handle = session.publish(event)
 *
 * // Check current status
 * when (handle.status) {
 *     is PublishStatus.Accepted -> println("Published!")
 *     is PublishStatus.Rejected -> println("Rejected: ${status.message}")
 *     // ...
 * }
 * ```
 *
 * Runtime implementations may provide additional methods for
 * suspending until completion or observing status changes via Flow.
 */
interface PublishHandle {
    /**
     * The event ID being published.
     */
    val eventId: EventId

    /**
     * The current status of the publish operation.
     */
    val status: PublishStatus

    /**
     * Whether the publish has reached a terminal state.
     *
     * Terminal states are: Accepted, Rejected, Abandoned, Replaced.
     * Interrupted is NOT terminal as it may be retried.
     */
    val isTerminal: Boolean
        get() = status.isTerminal
}

/**
 * Status of a publish operation.
 */
sealed interface PublishStatus {
    /**
     * Whether this is a terminal (final) status.
     */
    val isTerminal: Boolean

    /**
     * The publish is queued but not yet sent.
     *
     * This occurs when the connection is being established or
     * when the publish is waiting to be processed.
     */
    data object Queued : PublishStatus {
        override val isTerminal: Boolean = false
    }

    /**
     * The publish has been sent, waiting for OK response.
     */
    data object Pending : PublishStatus {
        override val isTerminal: Boolean = false
    }

    /**
     * The publish is waiting to retry.
     *
     * @property attempt The retry attempt number (1 for first retry).
     * @property delayMs Delay before the retry attempt.
     */
    data class Retrying(
        val attempt: Int,
        val delayMs: Long,
    ) : PublishStatus {
        override val isTerminal: Boolean = false
    }

    /**
     * The relay accepted the event.
     *
     * @property message The message from the relay's OK response.
     *   May contain "duplicate:" if the event was already known.
     */
    data class Accepted(val message: String) : PublishStatus {
        override val isTerminal: Boolean = true
    }

    /**
     * The relay rejected the event.
     *
     * @property message The rejection reason from the relay.
     */
    data class Rejected(val message: String) : PublishStatus {
        override val isTerminal: Boolean = true
    }

    /**
     * The publish was interrupted by a disconnect.
     *
     * The final state is unknown - the relay may or may not have
     * received the event. The runtime may retry based on policy.
     */
    data object Interrupted : PublishStatus {
        override val isTerminal: Boolean = false
    }

    /**
     * The publish was abandoned after max retries.
     *
     * The runtime gave up retrying after multiple interruptions.
     */
    data object Abandoned : PublishStatus {
        override val isTerminal: Boolean = true
    }

    /**
     * The publish was replaced by a newer attempt for the same event ID.
     *
     * @property reason Optional replacement reason.
     */
    data class Replaced(val reason: String? = null) : PublishStatus {
        override val isTerminal: Boolean = true
    }
}
