package nostr.runtime.coroutines

/**
 * Configuration for eager retry behavior on foreground operations.
 *
 * Eager operations like [CoroutineNostrRuntime.requestOne] and [CoroutineNostrRuntime.query]
 * automatically handle connection management and retry logic. This configuration controls
 * how aggressive that retry behavior is.
 */
data class EagerRetryConfig(
    /**
     * Maximum number of retry attempts after initial failure.
     * A value of 2 means up to 3 total attempts (1 initial + 2 retries).
     */
    val maxRetries: Int = 2,

    /**
     * Minimum time budget required to attempt a retry (milliseconds).
     * If less than this time remains in the overall timeout, no retry will be attempted.
     */
    val minRetryBudgetMillis: Long = 2000,

    /**
     * Number of consecutive timeouts-while-connected before triggering reconnect.
     *
     * A single timeout on a slow relay shouldn't cause reconnection churn.
     * Set to 1 for aggressive stale detection, higher for patience with slow relays.
     *
     * This prevents the SDK from thrashing connections on legitimately slow relays
     * while still detecting truly stale (half-open) connections.
     */
    val staleTimeoutThreshold: Int = 2,

    /**
     * Maximum time to wait for connection to establish during eager reconnect (milliseconds).
     * This is separate from the operation timeout and prevents blocking indefinitely
     * on connection attempts.
     */
    val eagerConnectTimeoutMillis: Long = 5000,

    /**
     * Maximum time to wait for write confirmation (milliseconds), or null to skip confirmation.
     *
     * When set, the SDK awaits confirmation that frames are actually written to the socket,
     * enabling fast detection of dead connections. If the write times out or fails,
     * the operation returns [RequestResult.ConnectionFailed] immediately.
     *
     * Set to null for fire-and-forget semantics (background apps).
     * Set to a short value (e.g., 3000ms) for fail-fast semantics (foreground apps).
     */
    val writeTimeoutMillis: Long? = null
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(minRetryBudgetMillis > 0) { "minRetryBudgetMillis must be positive" }
        require(staleTimeoutThreshold >= 1) { "staleTimeoutThreshold must be at least 1" }
        require(eagerConnectTimeoutMillis > 0) { "eagerConnectTimeoutMillis must be positive" }
        require(writeTimeoutMillis == null || writeTimeoutMillis > 0) { "writeTimeoutMillis must be null or positive" }
    }

    companion object {
        /**
         * Default configuration suitable for most use cases.
         * - 2 retries (3 total attempts)
         * - 2 consecutive timeouts before assuming stale connection
         * - 5 second connection timeout per attempt
         */
        val Default = EagerRetryConfig()

        /**
         * No retry behavior - fail immediately on first error.
         * Useful for testing or when the caller handles retries externally.
         */
        val NoRetry = EagerRetryConfig(
            maxRetries = 0,
            staleTimeoutThreshold = Int.MAX_VALUE
        )

        /**
         * Aggressive retry configuration for critical foreground operations.
         * - 3 retries (4 total attempts)
         * - Single timeout triggers reconnect (aggressive stale detection)
         * - Write confirmation with 3 second timeout (fail-fast on dead connections)
         */
        val Aggressive = EagerRetryConfig(
            maxRetries = 3,
            staleTimeoutThreshold = 1,
            writeTimeoutMillis = 3000
        )

        /**
         * Patient configuration for slow relays.
         * - 2 retries (3 total attempts)
         * - 4 consecutive timeouts before assuming stale connection
         * - 10 second connection timeout
         */
        val Patient = EagerRetryConfig(
            maxRetries = 2,
            staleTimeoutThreshold = 4,
            eagerConnectTimeoutMillis = 10_000
        )
    }
}
