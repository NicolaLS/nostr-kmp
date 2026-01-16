package io.github.nicolals.nostr.core.runtime

/**
 * Policy for how client-initiated subscription closes are handled.
 */
sealed interface ClosePolicy {
    /**
     * Send CLOSE and immediately mark the subscription closed locally.
     */
    data object Immediate : ClosePolicy

    /**
     * Send CLOSE and wait indefinitely for CLOSED.
     */
    data object WaitForever : ClosePolicy

    /**
     * Send CLOSE and wait for CLOSED up to [timeoutMs].
     *
     * If the timeout elapses, the subscription is closed locally.
     */
    data class Timeout(val timeoutMs: Long) : ClosePolicy
}
