package io.github.nicolals.nostr.core.runtime

/**
 * Configuration for a [RelaySession].
 *
 * Bundles all policy configurations that control session behavior.
 *
 * ## Example
 *
 * ```kotlin
 * val config = SessionConfig(
 *     reconnectPolicy = ReconnectPolicy.exponentialBackoff(
 *         maxAttempts = 10,
 *         baseDelayMs = 500,
 *     ),
 *     retryPolicy = RetryPolicy.default(),
 *     deduplicationPolicy = DeduplicationPolicy.lru(maxSize = 5000),
 *     closePolicy = ClosePolicy.Immediate,
 *     pingIntervalMillis = 30_000, // Send ping every 30 seconds
 * )
 * ```
 *
 * ## Default Configuration
 *
 * The default configuration provides sensible defaults:
 * - Reconnect with exponential backoff (5 attempts, 1-30s delays)
 * - Retry interrupted publishes (3 attempts) and resubscribe
 * - Deduplicate events (10,000 event window)
 * - Close subscriptions immediately on client request
 * - No automatic pinging (null pingIntervalMillis)
 *
 * @property reconnectPolicy How to handle disconnects.
 * @property retryPolicy How to handle interrupted operations after reconnect.
 * @property deduplicationPolicy How to filter duplicate events.
 * @property closePolicy How to handle client-initiated subscription close.
 * @property pingIntervalMillis Interval in milliseconds between WebSocket pings.
 *   When set, the session will periodically send pings to detect dead connections.
 *   Useful for detecting network issues faster than TCP timeouts.
 *   Set to null (default) to disable automatic pinging.
 */
data class SessionConfig(
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.exponentialBackoff(),
    val retryPolicy: RetryPolicy = RetryPolicy.default(),
    val deduplicationPolicy: DeduplicationPolicy = DeduplicationPolicy.lru(),
    val closePolicy: ClosePolicy = ClosePolicy.Immediate,
    val pingIntervalMillis: Long? = null,
) {
    init {
        require(pingIntervalMillis == null || pingIntervalMillis > 0) {
            "pingIntervalMillis must be positive or null, got: $pingIntervalMillis"
        }
    }

    companion object {
        /**
         * Default configuration with sensible defaults for most use cases.
         */
        val Default: SessionConfig = SessionConfig()

        /**
         * Minimal configuration that disables automatic reconnection and retry.
         *
         * Useful for testing or when you want full manual control.
         */
        val Minimal: SessionConfig = SessionConfig(
            reconnectPolicy = ReconnectPolicy.never(),
            retryPolicy = RetryPolicy.never(),
            deduplicationPolicy = DeduplicationPolicy.none(),
            closePolicy = ClosePolicy.Immediate,
            pingIntervalMillis = null,
        )
    }
}
