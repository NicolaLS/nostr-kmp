package io.github.nicolals.nostr.client.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.core.model.Filter
import nostr.runtime.coroutines.SharedSubscription
import nostr.runtime.coroutines.SmartRelaySession

/**
 * Manages a pool of [SharedSubscription] instances for efficient request-response patterns.
 *
 * Instead of creating a new subscription per request, the pool reuses existing subscriptions
 * that match the same filter. This reduces relay overhead for high-frequency operations
 * like multiple NWC calls.
 */
internal class SubscriptionPool(
    private val smartSession: SmartRelaySession
) {
    private val mutex = Mutex()
    private val subscriptions = mutableMapOf<Filter, SharedSubscription>()
    private var closed = false

    /**
     * Get or create a [SharedSubscription] for the given filter.
     *
     * If a subscription already exists for this filter, it is reused.
     * Otherwise, a new subscription is created and started.
     */
    suspend fun getOrCreate(filter: Filter): SharedSubscription {
        mutex.withLock {
            check(!closed) { "SubscriptionPool is closed" }

            // Check if we have an existing subscription that's still valid
            val existing = subscriptions[filter]
            if (existing != null && !existing.isClosed()) {
                return existing
            }

            // Remove closed subscription if any
            if (existing != null) {
                subscriptions.remove(filter)
            }
        }

        // Create new subscription outside the lock (it's a suspend function)
        val subscription = smartSession.createSharedSubscription(listOf(filter))

        mutex.withLock {
            // Double-check another coroutine didn't create one while we were waiting
            val maybeCreated = subscriptions[filter]
            if (maybeCreated != null && !maybeCreated.isClosed()) {
                // Another coroutine created one, close ours and use theirs
                runCatching { subscription.close() }
                return maybeCreated
            }

            subscriptions[filter] = subscription
            return subscription
        }
    }

    /**
     * Close all subscriptions in the pool.
     */
    suspend fun close() {
        val toClose: Collection<SharedSubscription>
        mutex.withLock {
            if (closed) return
            closed = true
            toClose = subscriptions.values.toList()
            subscriptions.clear()
        }
        toClose.forEach { runCatching { it.close() } }
    }
}
