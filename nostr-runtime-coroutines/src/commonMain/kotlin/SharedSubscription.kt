package nostr.runtime.coroutines

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.session.RelaySessionOutput
import kotlin.random.Random

/**
 * Exception thrown when a subscription is rejected by the relay.
 */
class SubscriptionRejectedException(
    val reason: String,
    val code: String?
) : Exception("Subscription rejected: $reason (code=$code)")

/**
 * Exception thrown when a network operation cannot proceed because
 * the device has no network connectivity.
 */
class NetworkUnavailableException(
    message: String = "No network connection available"
) : Exception(message)

/**
 * A long-lived subscription that routes incoming events to waiting callers
 * based on correlation IDs. Enables efficient request-response patterns
 * without creating a new subscription per request.
 *
 * Thread-safe and multiplatform-compatible.
 *
 * ## Usage Pattern
 * ```kotlin
 * val subscription = runtime.createSharedSubscription(filters)
 *
 * // Register expectation BEFORE publishing (critical for race-free operation)
 * val response = subscription.expectAndPublish(
 *     correlationId = requestEvent.id,
 *     publish = { runtime.publish(requestEvent) },
 *     timeoutMillis = 30_000
 * )
 * ```
 *
 * ## Correlation ID Extraction
 * By default, correlation IDs are extracted from the first 'e' tag of incoming events.
 * This matches the NWC (Nostr Wallet Connect) convention where response events
 * reference the request event ID via an 'e' tag.
 *
 * For custom correlation logic, provide a [correlationExtractor] when creating
 * the subscription.
 */
class SharedSubscription internal constructor(
    val subscriptionId: SubscriptionId,
    val filters: List<Filter>,
    private val runtime: CoroutineNostrRuntime,
    private val scope: CoroutineScope,
    private val correlationExtractor: (Event) -> String? = ::defaultCorrelationExtractor
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<Event>>()
    private val readyDeferred = CompletableDeferred<Unit>()
    private var collectorJob: Job? = null
    private var lifecycleJob: Job? = null
    private var closed = false

    /**
     * Wait for the subscription to be fully established with the relay.
     * Returns true when SubscriptionRegistered output is received.
     * Returns false if SubscriptionTerminated is received before registration or if closed.
     */
    suspend fun awaitReady(): Boolean {
        return runCatching { readyDeferred.await() }.isSuccess
    }

    /**
     * Register a correlation ID expectation, execute a publish action, then wait for response.
     *
     * CRITICAL: This method ensures the expectation is registered BEFORE publishing,
     * preventing race conditions where a fast relay responds before we're listening.
     *
     * If [writeTimeoutMillis] is non-null, the write confirmation is awaited before waiting
     * for the response. If the write times out or fails, returns null immediately (no point
     * waiting for a response if we couldn't even send the request).
     *
     * @param correlationId Unique ID to match against incoming event tags
     * @param publish Action that publishes the request and returns write confirmation
     * @param timeoutMillis Total time budget for the operation (write + response)
     * @param writeTimeoutMillis Optional timeout for write confirmation (null = don't wait for confirmation)
     * @return The matching event, or null if timeout/closed/write failed
     * @throws IllegalStateException if correlationId is already registered
     */
    suspend fun expectAndPublish(
        correlationId: String,
        publish: suspend () -> Deferred<WriteOutcome>,
        timeoutMillis: Long,
        writeTimeoutMillis: Long? = null
    ): Event? {
        if (timeoutMillis <= 0) return null
        val deferred = CompletableDeferred<Event>()

        // Register expectation FIRST
        mutex.withLock {
            if (closed) return null
            if (pending.containsKey(correlationId)) {
                throw IllegalStateException(
                    "Duplicate correlationId: $correlationId already registered"
                )
            }
            pending[correlationId] = deferred
        }

        return try {
            withTimeoutOrNull(timeoutMillis) {
                val writeConfirmation = publish()

                if (writeTimeoutMillis != null) {
                    val writeOutcome = withTimeoutOrNull(writeTimeoutMillis) { writeConfirmation.await() }
                        ?: WriteOutcome.Timeout
                    if (!writeOutcome.isSuccess()) {
                        return@withTimeoutOrNull null
                    }
                }

                deferred.await()
            }
        } finally {
            mutex.withLock { pending.remove(correlationId) }
        }
    }

    /**
     * Register expectation for a correlation ID and wait.
     *
     * WARNING: Prefer [expectAndPublish] to avoid race conditions.
     * Only use this if you've already registered the expectation through other means
     * or if the events are not correlated to a specific publish action.
     *
     * @param correlationId Unique ID to match against incoming event tags
     * @param timeoutMillis Maximum time to wait for response
     * @return The matching event, or null if timeout/closed
     * @throws IllegalStateException if correlationId is already registered
     */
    suspend fun expect(
        correlationId: String,
        timeoutMillis: Long
    ): Event? {
        val deferred = CompletableDeferred<Event>()

        mutex.withLock {
            if (closed) return null
            if (pending.containsKey(correlationId)) {
                throw IllegalStateException(
                    "Duplicate correlationId: $correlationId already registered"
                )
            }
            pending[correlationId] = deferred
        }

        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            mutex.withLock { pending.remove(correlationId) }
        }
    }

    /**
     * Close the subscription and cancel all pending waiters.
     * After closing, the subscription cannot be reused.
     */
    suspend fun close() {
        val toCancel: Collection<CompletableDeferred<Event>>
        mutex.withLock {
            if (closed) return
            closed = true
            toCancel = pending.values.toList()
            pending.clear()
        }
        toCancel.forEach { it.cancel() }
        collectorJob?.cancelAndJoin()
        lifecycleJob?.cancelAndJoin()
        runCatching { runtime.unsubscribe(subscriptionId) }
    }

    /**
     * Check if this subscription is closed.
     */
    suspend fun isClosed(): Boolean = mutex.withLock { closed }

    /**
     * Check if this subscription uses the given filters.
     * Used to validate filter compatibility for reuse.
     */
    fun matchesFilters(other: List<Filter>): Boolean {
        return filters == other
    }

    internal suspend fun start() {
        // Start collector FIRST to avoid missing early responses
        collectorJob = scope.launch {
            runtime.outputs
                .filterIsInstance<RelaySessionOutput.EventReceived>()
                .filter { it.subscriptionId == subscriptionId }
                .collect { output ->
                    routeEvent(output.event)
                }
        }

        // Also monitor for subscription lifecycle events
        lifecycleJob = scope.launch {
            runtime.outputs.collect { output ->
                when (output) {
                    is RelaySessionOutput.SubscriptionRegistered -> {
                        if (output.subscriptionId == subscriptionId) {
                            readyDeferred.complete(Unit)
                        }
                    }
                    is RelaySessionOutput.SubscriptionTerminated -> {
                        if (output.subscriptionId == subscriptionId) {
                            readyDeferred.completeExceptionally(
                                SubscriptionRejectedException(output.reason, output.code)
                            )
                            close()
                        }
                    }
                    else -> {}
                }
            }
        }

        // Now subscribe - collector is already listening
        runtime.subscribe(subscriptionId, filters)
    }

    private suspend fun routeEvent(event: Event) {
        val correlationId = correlationExtractor(event) ?: return
        val deferred = mutex.withLock { pending[correlationId] }
        deferred?.complete(event)
    }

    companion object {
        /**
         * Generate a unique subscription ID with random suffix.
         */
        fun generateId(prefix: String = "shared"): SubscriptionId {
            val suffix = Random.nextBytes(8).joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            return SubscriptionId("$prefix-$suffix")
        }
    }
}

/**
 * Default correlation extractor that looks for the first 'e' tag.
 * This matches NWC convention where response events reference request event ID.
 */
private fun defaultCorrelationExtractor(event: Event): String? {
    return event.tags
        .firstOrNull { it.size >= 2 && it[0] == "e" }
        ?.get(1)
}
