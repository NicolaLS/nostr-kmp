package io.github.nicolals.nostr.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nostr.core.model.PublishResult

/**
 * Handle for tracking publish acknowledgments from relays.
 *
 * This allows both fire-and-forget usage (ignore the handle) and
 * explicit ACK tracking when needed.
 *
 * ## Usage
 *
 * ```kotlin
 * // Fire and forget - just ignore the handle
 * client.publish(event)
 *
 * // Wait for all relays to acknowledge
 * val handle = client.publish(event)
 * val results = handle.awaitAll(timeoutMillis = 5000)
 *
 * // Wait for specific relay
 * val result = handle.await("wss://relay.damus.io", timeoutMillis = 5000)
 *
 * // Check immediate status
 * handle.targetedRelays  // Which relays were targeted
 * handle.results         // Results received so far
 * ```
 */
class PublishHandle internal constructor(
    /** The event ID that was published. */
    val eventId: String,
    /** List of relay URLs that were targeted for this publish. */
    val targetedRelays: List<String>
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<PublishResult>>()
    private val completed = mutableMapOf<String, PublishResult>()

    init {
        targetedRelays.forEach { url ->
            pending[url] = CompletableDeferred()
        }
    }

    /**
     * Results received so far, keyed by relay URL.
     */
    val results: Map<String, PublishResult>
        get() = completed.toMap()

    /**
     * List of relays that have acknowledged the publish.
     */
    val confirmedRelays: List<String>
        get() = completed.keys.toList()

    /**
     * List of relays that have accepted the publish (OK with accepted=true).
     */
    val acceptedRelays: List<String>
        get() = completed.filter { it.value.accepted }.keys.toList()

    /**
     * List of relays that have rejected the publish (OK with accepted=false).
     */
    val rejectedRelays: List<String>
        get() = completed.filter { !it.value.accepted }.keys.toList()

    /**
     * List of relays still waiting for acknowledgment.
     */
    val pendingRelays: List<String>
        get() = pending.keys.minus(completed.keys).toList()

    /**
     * Wait for acknowledgment from a specific relay.
     *
     * @param url Relay URL to wait for
     * @param timeoutMillis Maximum time to wait
     * @return PublishResult if received within timeout, null otherwise
     */
    suspend fun await(url: String, timeoutMillis: Long): PublishResult? {
        val deferred = mutex.withLock { pending[url] } ?: return completed[url]
        return withTimeoutOrNull(timeoutMillis) { deferred.await() }
    }

    /**
     * Wait for acknowledgments from all targeted relays.
     *
     * @param timeoutMillis Maximum time to wait for all acknowledgments
     * @return Map of relay URL to result. Relays that didn't respond in time will have null values.
     */
    suspend fun awaitAll(timeoutMillis: Long): Map<String, PublishResult?> {
        return withTimeoutOrNull(timeoutMillis) {
            val results = mutableMapOf<String, PublishResult?>()
            val deferreds = mutex.withLock { pending.toMap() }

            for ((url, deferred) in deferreds) {
                results[url] = deferred.await()
            }
            results
        } ?: targetedRelays.associateWith { completed[it] }
    }

    /**
     * Wait for at least one relay to acknowledge.
     *
     * @param timeoutMillis Maximum time to wait
     * @return First PublishResult received, or null if timeout
     */
    suspend fun awaitFirst(timeoutMillis: Long): Pair<String, PublishResult>? {
        // If we already have results, return the first one
        completed.entries.firstOrNull()?.let { return it.key to it.value }

        // Wait for any deferred to complete
        return withTimeoutOrNull(timeoutMillis) {
            val deferreds = mutex.withLock { pending.toList() }
            if (deferreds.isEmpty()) return@withTimeoutOrNull null

            // Use select to wait for any to complete
            select {
                deferreds.forEach { (url, deferred) ->
                    deferred.onAwait { result -> url to result }
                }
            }
        }
    }

    /**
     * Check if all targeted relays have responded.
     */
    val isComplete: Boolean
        get() = completed.size == targetedRelays.size

    /**
     * Check if at least one relay has accepted the publish.
     */
    val hasAccepted: Boolean
        get() = completed.values.any { it.accepted }

    /**
     * Internal: Called when a relay acknowledges the publish.
     */
    internal suspend fun onResult(url: String, result: PublishResult) {
        mutex.withLock {
            completed[url] = result
            pending[url]?.complete(result)
        }
    }
}
