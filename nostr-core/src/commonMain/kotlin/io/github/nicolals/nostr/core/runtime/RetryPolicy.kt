package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * Policy for retrying interrupted operations after reconnection.
 *
 * When a connection is lost, pending publishes and active subscriptions
 * are marked as "interrupted". This policy determines how to handle
 * them after reconnecting.
 */
interface RetryPolicy {

    /**
     * Determines whether to retry a publish that was interrupted.
     *
     * @param eventId The event ID of the interrupted publish.
     * @param attempt The retry attempt number (1 for first retry).
     * @return The decision: retry with optional delay or abandon.
     */
    fun shouldRetryPublish(eventId: EventId, attempt: Int): RetryDecision

    /**
     * Determines whether to resubscribe after an interruption or relay close.
     *
     * @param subscriptionId The subscription ID that was interrupted.
     * @param attempt The retry attempt number (1 for first retry).
     * @param reason The reason the subscription needs to be retried.
     * @return The decision: retry with optional delay or abandon.
     */
    fun shouldResubscribe(
        subscriptionId: SubscriptionId,
        attempt: Int,
        reason: SubscriptionRetryReason,
    ): RetryDecision

    companion object {
        /**
         * Creates a default retry policy.
         *
         * - Retries publishes up to 3 times with exponential backoff
         * - Always resubscribes to disconnected subscriptions
         * - Does not resubscribe on relay CLOSED by default
         */
        fun default(): RetryPolicy = DefaultRetryPolicy()

        /**
         * Creates a policy that never retries.
         *
         * - Never retries interrupted publishes
         * - Never resubscribes to subscriptions
         */
        fun never(): RetryPolicy = NeverRetryPolicy

        /**
         * Creates a policy that always retries.
         *
         * - Retries publishes indefinitely with the given delay
         * - Always resubscribes to subscriptions with the same delay
         *
         * @param publishRetryDelayMs Delay between publish retry attempts.
         */
        fun always(publishRetryDelayMs: Long = 1000): RetryPolicy =
            AlwaysRetryPolicy(publishRetryDelayMs)
    }
}

/**
 * Reason for retrying a subscription.
 */
sealed interface SubscriptionRetryReason {
    /**
     * The connection was lost while the subscription was active.
     */
    data object Disconnected : SubscriptionRetryReason

    /**
     * The relay closed the subscription with a CLOSED message.
     *
     * @property reason The close reason, if provided.
     * @property closeReason Parsed close reason, if recognized.
     */
    data class Closed(
        val reason: String?,
        val closeReason: CloseReason? = reason?.let { CloseReason.Other(it) },
    ) : SubscriptionRetryReason
}

/**
 * The decision from a retry policy for publishes.
 */
sealed interface RetryDecision {
    /**
     * Retry the publish after the specified delay.
     *
     * @property delayMs Delay in milliseconds before retrying. Use 0 for immediate retry.
     */
    data class Retry(val delayMs: Long = 0) : RetryDecision

    /**
     * Abandon the publish - don't retry.
     */
    data object Abandon : RetryDecision
}

/**
 * Default retry policy with sensible defaults.
 */
internal class DefaultRetryPolicy(
    private val maxPublishRetries: Int = 3,
    private val baseRetryDelayMs: Long = 500,
) : RetryPolicy {

    override fun shouldRetryPublish(eventId: EventId, attempt: Int): RetryDecision {
        if (attempt > maxPublishRetries) {
            return RetryDecision.Abandon
        }
        // Exponential backoff: 500ms, 1s, 2s
        val delay = baseRetryDelayMs * (1L shl (attempt - 1).coerceAtMost(10))
        return RetryDecision.Retry(delay)
    }

    override fun shouldResubscribe(
        subscriptionId: SubscriptionId,
        attempt: Int,
        reason: SubscriptionRetryReason,
    ): RetryDecision {
        return when (reason) {
            SubscriptionRetryReason.Disconnected -> RetryDecision.Retry(0)
            is SubscriptionRetryReason.Closed -> RetryDecision.Abandon
        }
    }
}

/**
 * Policy that never retries anything.
 */
internal data object NeverRetryPolicy : RetryPolicy {
    override fun shouldRetryPublish(eventId: EventId, attempt: Int): RetryDecision =
        RetryDecision.Abandon

    override fun shouldResubscribe(
        subscriptionId: SubscriptionId,
        attempt: Int,
        reason: SubscriptionRetryReason,
    ): RetryDecision = RetryDecision.Abandon
}

/**
 * Policy that always retries indefinitely.
 */
internal class AlwaysRetryPolicy(
    private val publishRetryDelayMs: Long,
) : RetryPolicy {
    override fun shouldRetryPublish(eventId: EventId, attempt: Int): RetryDecision =
        RetryDecision.Retry(publishRetryDelayMs)

    override fun shouldResubscribe(
        subscriptionId: SubscriptionId,
        attempt: Int,
        reason: SubscriptionRetryReason,
    ): RetryDecision = RetryDecision.Retry(publishRetryDelayMs)
}
