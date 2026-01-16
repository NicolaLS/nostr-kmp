package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.transport.TransportError

/**
 * Policy for handling reconnection after disconnects.
 *
 * Implementations determine when and how to reconnect based on the
 * number of attempts and the error that caused the disconnect.
 */
fun interface ReconnectPolicy {

    /**
     * Determines whether to reconnect and with what delay.
     *
     * @param attempt The current attempt number (1 for first reconnect attempt).
     * @param error The transport error that caused the disconnect, if any.
     *   May be null for clean disconnects initiated by the relay.
     * @return The decision: reconnect with delay or give up.
     */
    fun shouldReconnect(attempt: Int, error: TransportError?): ReconnectDecision

    companion object {
        /**
         * Creates a policy with exponential backoff.
         *
         * Delay doubles with each attempt, capped at [maxDelayMs].
         * Gives up after [maxAttempts].
         *
         * @param maxAttempts Maximum number of reconnection attempts before giving up.
         * @param baseDelayMs Initial delay in milliseconds.
         * @param maxDelayMs Maximum delay cap in milliseconds.
         * @param jitterFactor Random jitter factor (0.0 to 1.0). Default is 0.1 (10%).
         */
        fun exponentialBackoff(
            maxAttempts: Int = 5,
            baseDelayMs: Long = 1000,
            maxDelayMs: Long = 30_000,
            jitterFactor: Double = 0.1,
        ): ReconnectPolicy = ExponentialBackoffReconnectPolicy(
            maxAttempts = maxAttempts,
            baseDelayMs = baseDelayMs,
            maxDelayMs = maxDelayMs,
            jitterFactor = jitterFactor,
        )

        /**
         * Creates a policy that never reconnects.
         */
        fun never(): ReconnectPolicy = NeverReconnectPolicy
    }
}

/**
 * The decision from a [ReconnectPolicy].
 */
sealed interface ReconnectDecision {
    /**
     * Reconnect after the specified delay.
     *
     * @property delayMs Delay in milliseconds before attempting reconnection.
     */
    data class Reconnect(val delayMs: Long) : ReconnectDecision

    /**
     * Give up reconnecting.
     */
    data object GiveUp : ReconnectDecision
}

/**
 * Exponential backoff policy with jitter.
 */
internal class ExponentialBackoffReconnectPolicy(
    private val maxAttempts: Int,
    private val baseDelayMs: Long,
    private val maxDelayMs: Long,
    private val jitterFactor: Double,
) : ReconnectPolicy {

    override fun shouldReconnect(attempt: Int, error: TransportError?): ReconnectDecision {
        if (attempt > maxAttempts) {
            return ReconnectDecision.GiveUp
        }

        // Calculate exponential delay: baseDelay * 2^(attempt-1)
        val exponentialDelay = baseDelayMs * (1L shl (attempt - 1).coerceAtMost(30))
        val cappedDelay = exponentialDelay.coerceAtMost(maxDelayMs)

        // Add jitter
        val jitter = (cappedDelay * jitterFactor * kotlin.random.Random.nextDouble()).toLong()
        val finalDelay = cappedDelay + jitter

        return ReconnectDecision.Reconnect(finalDelay)
    }
}

/**
 * Policy that never reconnects.
 */
internal data object NeverReconnectPolicy : ReconnectPolicy {
    override fun shouldReconnect(attempt: Int, error: TransportError?): ReconnectDecision {
        return ReconnectDecision.GiveUp
    }
}
