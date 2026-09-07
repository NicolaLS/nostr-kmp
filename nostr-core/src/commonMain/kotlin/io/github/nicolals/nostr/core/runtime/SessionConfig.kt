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
 *     deduplicationPolicyFactory = { DeduplicationPolicy.lru(maxSize = 5000) },
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
 * @property deduplicationPolicyFactory Creates independent deduplication state for each
 *   logical subscription. The same subscription retains its cache across reconnections.
 *   The factory must return a fresh policy unless that policy is stateless.
 * @property closePolicy How to handle client-initiated subscription close.
 * @property pingIntervalMillis Interval in milliseconds between WebSocket pings.
 *   When set, the session will periodically send pings to detect dead connections.
 *   Useful for detecting network issues faster than TCP timeouts.
 *   Set to null (default) to disable automatic pinging.
 */
data class SessionConfig(
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.exponentialBackoff(),
    val retryPolicy: RetryPolicy = RetryPolicy.default(),
    val deduplicationPolicyFactory: () -> DeduplicationPolicy = { DeduplicationPolicy.lru() },
    val closePolicy: ClosePolicy = ClosePolicy.Immediate,
    val pingIntervalMillis: Long? = null,
    /** Maximum queued signals or inbound messages per runtime observer. */
    val observerBufferCapacity: Int = 64,
    /** Maximum buffered events for each subscription. */
    val subscriptionBufferCapacity: Int = 256,
    /** Fail incomplete subscriptions by default; dropping must be explicitly selected. */
    val subscriptionOverflowPolicy: SubscriptionOverflowPolicy = SubscriptionOverflowPolicy.Fail,

) {
    init {
        require(subscriptionBufferCapacity > 0) { "subscriptionBufferCapacity must be positive" }
        require(observerBufferCapacity > 0) { "observerBufferCapacity must be positive" }
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
            deduplicationPolicyFactory = { DeduplicationPolicy.none() },
            closePolicy = ClosePolicy.Immediate,
            pingIntervalMillis = null,
        )
    }
}

/** Behavior when a subscription consumer cannot keep up with incoming events. */
enum class SubscriptionOverflowPolicy {
    /** Close the subscription and fail its event flow, preserving a visible incomplete result. */
    Fail,
    /** Drop the newest event, expose a cumulative drop count, and allow later replay. */
    DropNewest,
}
