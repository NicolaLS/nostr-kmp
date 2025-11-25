package nostr.runtime.coroutines

import kotlin.math.min
import kotlin.random.Random

/**
 * Strategy for determining when and how to reconnect after connection failures.
 *
 * Implementations receive information about the current failure and return a decision
 * about whether to retry and, if so, after what delay.
 */
fun interface ReconnectionPolicy {
    /**
     * Determines the reconnection behavior after a failure.
     *
     * @param attempt The current attempt number (1-based, increments with each retry for the same URL).
     * @param failure Details about the connection failure, or null for graceful closes.
     * @return The delay in milliseconds before reconnecting, or null to not reconnect.
     */
    fun nextDelay(attempt: Int, failure: ConnectionFailure?): Long?
}

/**
 * A reconnection policy that disables automatic reconnection.
 * Use this when you want full manual control over connection lifecycle.
 */
object NoReconnectionPolicy : ReconnectionPolicy {
    override fun nextDelay(attempt: Int, failure: ConnectionFailure?): Long? = null
}

/**
 * Reconnection policy using exponential backoff with optional jitter.
 *
 * The delay doubles with each attempt: baseDelayMillis, 2x, 4x, 8x, ... up to maxDelayMillis.
 * Jitter (when enabled) adds randomness to prevent thundering herd problems.
 *
 * @property baseDelayMillis Initial delay for the first retry (default: 500ms).
 * @property maxDelayMillis Maximum delay cap (default: 15 seconds).
 * @property maxAttempts Maximum number of retry attempts before giving up (default: 10, null = unlimited).
 * @property jitterFactor Random factor applied to delay (0.0 = no jitter, 0.5 = +/- 50%, default: 0.25).
 */
class ExponentialBackoffPolicy(
    private val baseDelayMillis: Long = 500L,
    private val maxDelayMillis: Long = 15_000L,
    private val maxAttempts: Int? = 10,
    private val jitterFactor: Double = 0.25,
    private val random: Random = Random.Default
) : ReconnectionPolicy {

    init {
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be >= baseDelayMillis" }
        require(maxAttempts == null || maxAttempts > 0) { "maxAttempts must be positive or null" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be between 0.0 and 1.0" }
    }

    override fun nextDelay(attempt: Int, failure: ConnectionFailure?): Long? {
        // Check if we've exceeded max attempts
        if (maxAttempts != null && attempt >= maxAttempts) {
            return null
        }

        // Calculate exponential delay: base * 2^(attempt-1), capped at max
        val exponentialDelay = baseDelayMillis * (1L shl min(attempt - 1, 30))
        val cappedDelay = min(exponentialDelay, maxDelayMillis)

        // Apply jitter
        return if (jitterFactor > 0.0) {
            val jitterRange = (cappedDelay * jitterFactor).toLong()
            val jitter = random.nextLong(-jitterRange, jitterRange + 1)
            (cappedDelay + jitter).coerceAtLeast(1L)
        } else {
            cappedDelay
        }
    }
}

/**
 * Reconnection policy with a fixed delay between attempts.
 *
 * @property delayMillis Constant delay between reconnection attempts.
 * @property maxAttempts Maximum number of retry attempts (null = unlimited).
 */
class FixedDelayPolicy(
    private val delayMillis: Long = 1_000L,
    private val maxAttempts: Int? = 5
) : ReconnectionPolicy {

    init {
        require(delayMillis > 0) { "delayMillis must be positive" }
        require(maxAttempts == null || maxAttempts > 0) { "maxAttempts must be positive or null" }
    }

    override fun nextDelay(attempt: Int, failure: ConnectionFailure?): Long? {
        if (maxAttempts != null && attempt >= maxAttempts) {
            return null
        }
        return delayMillis
    }
}
