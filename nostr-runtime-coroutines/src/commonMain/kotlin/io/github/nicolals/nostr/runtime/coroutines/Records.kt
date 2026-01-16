package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.runtime.SubscriptionRetryReason

/**
 * Internal record tracking a subscription with its handle and metadata.
 *
 * @property subscriptionId The subscription identifier.
 * @property filters The filters for this subscription.
 * @property handle The handle exposed to consumers.
 * @property generation The current generation (for matching signals).
 * @property retryCount Number of retry attempts after interruption.
 */
internal data class SubscriptionRecord(
    val subscriptionId: SubscriptionId,
    val filters: List<Filter>,
    val handle: CoroutinesSubscriptionHandle,
    val generation: Int = 0,
    val retryCount: Int = 0,
    val lastRetryReason: SubscriptionRetryReason? = null,
)

/**
 * Internal record tracking a publish with its handle and metadata.
 *
 * @property eventId The event ID being published.
 * @property event The full event (needed for retries).
 * @property handle The handle exposed to consumers.
 * @property retryCount Number of retry attempts after interruption.
 */
internal data class PublishRecord(
    val eventId: EventId,
    val event: Event,
    val handle: CoroutinesPublishHandle,
    val retryCount: Int = 0,
)
