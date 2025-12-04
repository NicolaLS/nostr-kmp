package nostr.runtime.coroutines

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.RelaySessionIntent
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionState

/**
 * A smart wrapper around [CoroutineNostrRuntime] that provides higher-level
 * request-response patterns with automatic connection management.
 *
 * While [CoroutineNostrRuntime] provides raw NIP-01 protocol operations,
 * [SmartRelaySession] adds application-level conveniences:
 *
 * - **Auto-connect**: Operations automatically establish connections when needed
 * - **Request-response**: [requestOne] publishes and awaits a correlated response
 * - **Query**: [query] collects events until EOSE with retry semantics
 * - **Stale detection**: Automatically reconnects when connections appear dead
 * - **Shared subscriptions**: Efficient reuse for high-frequency operations
 *
 * ## Usage
 *
 * ```kotlin
 * // Create a smart session wrapping a runtime
 * val runtime = CoroutineNostrRuntime(...)
 * val smart = SmartRelaySession(runtime, "wss://relay.example.com")
 *
 * // Use high-level operations
 * when (val result = smart.requestOne(requestEvent, responseFilter)) {
 *     is RequestResult.Success -> handleResponse(result.value)
 *     is RequestResult.Timeout -> showRetryDialog()
 *     is RequestResult.ConnectionFailed -> showOfflineMessage()
 * }
 * ```
 *
 * ## Layer Architecture
 *
 * ```
 * ┌─────────────────────────────────────────────┐
 * │           SmartRelaySession                 │  ← You are here
 * │  (requestOne, query, auto-connect)          │
 * ├─────────────────────────────────────────────┤
 * │         CoroutineNostrRuntime               │
 * │  (connect, subscribe, publish - raw NIP-01) │
 * └─────────────────────────────────────────────┘
 * ```
 *
 * @param runtime The underlying runtime to wrap
 * @param url The relay URL this session manages
 * @param scope Optional coroutine scope for background operations (defaults to runtime's scope)
 */
class SmartRelaySession(
    private val runtime: CoroutineNostrRuntime,
    val url: String,
    private val scope: CoroutineScope = runtime.runtimeScope
) {
    private val reconnectMutex = Mutex()

    // ========================================================================
    // Delegated Properties - Expose underlying runtime state
    // ========================================================================

    /** Full session state including subscriptions and pending publishes. */
    val states: StateFlow<RelaySessionState> get() = runtime.states

    /** Stream of outputs (events, EOSE, notices, etc.) from the relay. */
    val outputs: SharedFlow<RelaySessionOutput> get() = runtime.outputs

    /** Current connection state snapshot. */
    val connectionSnapshots: StateFlow<ConnectionSnapshot> get() = runtime.connectionSnapshots

    /** Connection telemetry including retry information. */
    val connectionTelemetry: StateFlow<ConnectionTelemetry> get() = runtime.connectionTelemetry

    // ========================================================================
    // Delegated Methods - Pass through to runtime
    // ========================================================================

    /** Establish a connection to the relay. */
    suspend fun connect() = runtime.connect(url)

    /** Disconnect from the relay. */
    suspend fun disconnect(code: Int? = 1000, reason: String? = null) =
        runtime.disconnect(code, reason)

    /** Subscribe to events matching the given filters. */
    suspend fun subscribe(subscriptionId: SubscriptionId, filters: List<Filter>) =
        runtime.subscribe(subscriptionId, filters)

    /** Subscribe to events matching the given filters. */
    suspend fun subscribe(subscriptionId: String, filters: List<Filter>) =
        runtime.subscribe(subscriptionId, filters)

    /** Cancel an existing subscription. */
    suspend fun unsubscribe(subscriptionId: SubscriptionId) =
        runtime.unsubscribe(subscriptionId)

    /** Cancel an existing subscription. */
    suspend fun unsubscribe(subscriptionId: String) =
        runtime.unsubscribe(subscriptionId)

    /**
     * Publish an event to the relay.
     *
     * Returns a [Deferred] that completes when the write is confirmed at the transport level.
     * This enables detection of stale/dead connections:
     *
     * ```kotlin
     * // Fire-and-forget (background apps)
     * session.publish(event)
     *
     * // Await confirmation with timeout (foreground apps)
     * val outcome = withTimeoutOrNull(3000) { session.publish(event).await() }
     *     ?: WriteOutcome.Timeout
     * if (!outcome.isSuccess()) {
     *     // Connection is dead, handle error
     * }
     * ```
     */
    suspend fun publish(event: Event): Deferred<WriteOutcome> = runtime.publish(event)

    /** Respond to a NIP-42 authentication challenge. */
    suspend fun authenticate(event: Event) = runtime.authenticate(event)

    // ========================================================================
    // Smart Operations - Request-Response with Automatic Connection Management
    // ========================================================================

    /**
     * Publish an event and wait for a correlated response.
     *
     * Uses eager semantics: auto-connects if needed, retries on stale connections.
     * This is the primary method for foreground request-response operations
     * like NWC (Nostr Wallet Connect) calls.
     *
     * @param requestEvent The event to publish
     * @param responseFilter Filter to match the expected response
     * @param correlationId ID to correlate request with response (default: requestEvent.id)
     * @param timeoutMillis Total time budget for the operation
     * @param retryConfig Configuration for retry behavior
     * @return [RequestResult.Success] with the response event, or a failure result
     */
    suspend fun requestOne(
        requestEvent: Event,
        responseFilter: Filter,
        correlationId: String = requestEvent.id,
        timeoutMillis: Long = 30_000,
        retryConfig: EagerRetryConfig = EagerRetryConfig.Default
    ): RequestResult<Event> {
        val startTime = currentTimeMillis()
        var attempts = 0
        var consecutiveTimeouts = 0
        var lastError: String? = null

        while (true) {
            attempts++
            val elapsed = currentTimeMillis() - startTime
            val remaining = timeoutMillis - elapsed

            if (remaining <= 0) {
                return RequestResult.Timeout(elapsed)
            }

            val canRetry = attempts <= retryConfig.maxRetries + 1 &&
                remaining >= retryConfig.minRetryBudgetMillis

            val connectResult = awaitConnected(
                minOf(remaining, retryConfig.eagerConnectTimeoutMillis)
            )
            if (!connectResult) {
                lastError = "Failed to establish connection"
                if (canRetry) {
                    reconnectImmediate()
                    continue
                }
                return RequestResult.ConnectionFailed(attempts, lastError)
            }

            val result = tryRequestOnce(
                requestEvent = requestEvent,
                responseFilter = responseFilter,
                correlationId = correlationId,
                timeoutMillis = remaining
            )

            when (result) {
                is TryResult.Success -> {
                    return RequestResult.Success(result.event)
                }

                is TryResult.Timeout -> {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= retryConfig.staleTimeoutThreshold && isConnected()) {
                        reconnectImmediate()
                        lastError = "Timeout while connected (suspected stale after $consecutiveTimeouts timeouts)"
                        consecutiveTimeouts = 0
                        if (canRetry) continue
                    }
                    return RequestResult.Timeout(currentTimeMillis() - startTime)
                }

                is TryResult.ConnectionFailed -> {
                    consecutiveTimeouts = 0
                    lastError = result.message
                    if (canRetry) {
                        reconnectImmediate()
                        continue
                    }
                    return RequestResult.ConnectionFailed(attempts, lastError)
                }
            }
        }
    }

    /**
     * Use an existing [SharedSubscription] for efficient request-response.
     *
     * This is more efficient for high-frequency operations (like multiple NWC calls)
     * as it reuses a single subscription rather than creating one per request.
     *
     * @param subscription The shared subscription to use for receiving responses
     * @param requestEvent The event to publish
     * @param correlationId ID to correlate request with response (default: requestEvent.id)
     * @param timeoutMillis Total time budget for the operation
     * @param retryConfig Configuration for retry behavior
     */
    suspend fun requestOneVia(
        subscription: SharedSubscription,
        requestEvent: Event,
        correlationId: String = requestEvent.id,
        timeoutMillis: Long = 30_000,
        retryConfig: EagerRetryConfig = EagerRetryConfig.Default
    ): RequestResult<Event> {
        val startTime = currentTimeMillis()
        var attempts = 0
        var consecutiveTimeouts = 0
        var lastError: String? = null

        while (true) {
            attempts++
            val elapsed = currentTimeMillis() - startTime
            val remaining = timeoutMillis - elapsed

            if (remaining <= 0) {
                return RequestResult.Timeout(elapsed)
            }

            // Check network availability before attempting request (if enabled)
            if (retryConfig.checkNetworkBeforeRequest && !isNetworkAvailable()) {
                return RequestResult.ConnectionFailed(attempts, "No network connection available")
            }

            val canRetry = attempts <= retryConfig.maxRetries + 1 &&
                remaining >= retryConfig.minRetryBudgetMillis

            if (!subscription.awaitReady()) {
                return RequestResult.ConnectionFailed(attempts, "Subscription was rejected")
            }

            val connectResult = awaitConnected(
                minOf(remaining, retryConfig.eagerConnectTimeoutMillis)
            )
            if (!connectResult) {
                lastError = "Failed to establish connection"
                if (canRetry) {
                    reconnectImmediate()
                    continue
                }
                return RequestResult.ConnectionFailed(attempts, lastError)
            }

            val response = subscription.expectAndPublish(
                correlationId = correlationId,
                publish = { publish(requestEvent) },
                timeoutMillis = remaining,
                writeTimeoutMillis = retryConfig.writeTimeoutMillis
            )

            if (response != null) {
                return RequestResult.Success(response)
            }

            // If writeTimeoutMillis is set and we got null, it could be a write failure
            // indicating a dead connection. The expectAndPublish already handles this by
            // returning null immediately on write failure.
            if (retryConfig.writeTimeoutMillis != null) {
                // With write confirmation enabled, a null response likely means write failed
                // (dead connection) rather than a genuine timeout waiting for relay response
                if (canRetry) {
                    lastError = "Write confirmation failed or timed out"
                    reconnectImmediate()
                    continue
                }
                return RequestResult.ConnectionFailed(attempts, "Write failed - connection appears dead")
            }

            consecutiveTimeouts++
            if (consecutiveTimeouts >= retryConfig.staleTimeoutThreshold && isConnected()) {
                reconnectImmediate()
                lastError = "Timeout while connected (suspected stale)"
                consecutiveTimeouts = 0
                if (canRetry) continue
            }

            return RequestResult.Timeout(currentTimeMillis() - startTime)
        }
    }

    /**
     * Query for multiple events with eager retry semantics.
     *
     * Collects events until EOSE (End of Stored Events) or timeout.
     * Automatically handles connection and retries.
     *
     * @param filters Filters to match events
     * @param timeoutMillis Total time budget for the operation
     * @param retryConfig Configuration for retry behavior
     * @return [RequestResult.Success] with list of events, or a failure result
     */
    suspend fun query(
        filters: List<Filter>,
        timeoutMillis: Long = 30_000,
        retryConfig: EagerRetryConfig = EagerRetryConfig.Default
    ): RequestResult<List<Event>> {
        val startTime = currentTimeMillis()
        var attempts = 0
        var consecutiveTimeouts = 0
        var lastError: String? = null

        while (true) {
            attempts++
            val elapsed = currentTimeMillis() - startTime
            val remaining = timeoutMillis - elapsed

            if (remaining <= 0) {
                return RequestResult.Timeout(elapsed)
            }

            val canRetry = attempts <= retryConfig.maxRetries + 1 &&
                remaining >= retryConfig.minRetryBudgetMillis

            val connectResult = awaitConnected(
                minOf(remaining, retryConfig.eagerConnectTimeoutMillis)
            )
            if (!connectResult) {
                lastError = "Failed to establish connection"
                if (canRetry) {
                    reconnectImmediate()
                    continue
                }
                return RequestResult.ConnectionFailed(attempts, lastError)
            }

            val result = tryQueryOnce(filters, remaining)

            when (result) {
                is QueryResult.Success -> {
                    return RequestResult.Success(result.events)
                }

                is QueryResult.Timeout -> {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= retryConfig.staleTimeoutThreshold && isConnected()) {
                        reconnectImmediate()
                        lastError = "EOSE not received (suspected stale)"
                        consecutiveTimeouts = 0
                        if (canRetry) continue
                    }
                    return RequestResult.Timeout(currentTimeMillis() - startTime)
                }

                is QueryResult.ConnectionFailed -> {
                    consecutiveTimeouts = 0
                    lastError = result.message
                    if (canRetry) {
                        reconnectImmediate()
                        continue
                    }
                    return RequestResult.ConnectionFailed(attempts, lastError)
                }
            }
        }
    }

    /**
     * Create a [SharedSubscription] for efficient request-response patterns.
     *
     * The subscription starts collecting immediately and awaits registration
     * confirmation from the relay before returning. Use this for high-frequency
     * operations where creating a new subscription per request would be wasteful.
     *
     * @param filters Filters for the subscription
     * @param subscriptionId Optional custom subscription ID
     * @param correlationExtractor Optional custom function to extract correlation IDs from events
     */
    suspend fun createSharedSubscription(
        filters: List<Filter>,
        subscriptionId: SubscriptionId = SharedSubscription.generateId(),
        correlationExtractor: ((Event) -> String?)? = null
    ): SharedSubscription {
        val subscription = if (correlationExtractor != null) {
            SharedSubscription(subscriptionId, filters, runtime, scope, correlationExtractor)
        } else {
            SharedSubscription(subscriptionId, filters, runtime, scope)
        }
        subscription.start()
        subscription.awaitReady()
        return subscription
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    private sealed interface TryResult {
        data class Success(val event: Event) : TryResult
        data class Timeout(val partialEvents: List<Event> = emptyList()) : TryResult
        data class ConnectionFailed(val message: String?) : TryResult
    }

    private sealed interface QueryResult {
        data class Success(val events: List<Event>) : QueryResult
        data class Timeout(val partialEvents: List<Event>) : QueryResult
        data class ConnectionFailed(val message: String?) : QueryResult
    }

    /**
     * Wait for connection to be established, auto-connecting if not connected.
     */
    private suspend fun awaitConnected(timeoutMillis: Long): Boolean {
        val snapshot = connectionSnapshots.value

        if (snapshot is ConnectionSnapshot.Connected) return true

        if (snapshot !is ConnectionSnapshot.Connecting) {
            connect()
        }

        return withTimeoutOrNull(timeoutMillis) {
            connectionSnapshots.first { it is ConnectionSnapshot.Connected }
            true
        } ?: false
    }

    /**
     * Trigger immediate reconnection, bypassing normal backoff.
     */
    private suspend fun reconnectImmediate() {
        reconnectMutex.withLock {
            runtime.disconnect(1000, "Reconnecting")
            // Small delay to ensure disconnect is processed
            delay(50)
            connect()
        }
    }

    private fun isConnected(): Boolean {
        return states.value.connection is ConnectionSnapshot.Connected
    }

    private suspend fun tryRequestOnce(
        requestEvent: Event,
        responseFilter: Filter,
        correlationId: String,
        timeoutMillis: Long
    ): TryResult {
        if (!isConnected()) {
            return TryResult.ConnectionFailed("Not connected")
        }

        val subscriptionId = SharedSubscription.generateId("req")

        return try {
            coroutineScope {
                val events = mutableListOf<Event>()
                val resultDeferred = CompletableDeferred<TryResult>()

                // Start collecting BEFORE subscribing
                val collectorJob = launch {
                    outputs
                        .filterIsInstance<RelaySessionOutput.EventReceived>()
                        .filter { it.subscriptionId == subscriptionId }
                        .collect { output ->
                            val event = output.event
                            val eventCorrelationId = event.tags
                                .firstOrNull { it.size >= 2 && it[0] == "e" }
                                ?.get(1)
                            if (eventCorrelationId == correlationId) {
                                resultDeferred.complete(TryResult.Success(event))
                                cancel()
                            }
                        }
                }

                subscribe(subscriptionId, listOf(responseFilter))
                publish(requestEvent)

                val result = withTimeoutOrNull(timeoutMillis) {
                    resultDeferred.await()
                }

                collectorJob.cancelAndJoin()
                unsubscribe(subscriptionId)

                result ?: TryResult.Timeout(events)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TryResult.ConnectionFailed(e.message)
        }
    }

    private suspend fun tryQueryOnce(
        filters: List<Filter>,
        timeoutMillis: Long
    ): QueryResult {
        if (!isConnected()) {
            return QueryResult.ConnectionFailed("Not connected")
        }

        val subscriptionId = SharedSubscription.generateId("query")

        return try {
            coroutineScope {
                val events = mutableListOf<Event>()
                val eoseReceived = CompletableDeferred<Unit>()

                // Start collecting BEFORE subscribing
                val collectorJob = launch {
                    outputs.collect { output ->
                        when (output) {
                            is RelaySessionOutput.EventReceived -> {
                                if (output.subscriptionId == subscriptionId) {
                                    events.add(output.event)
                                }
                            }
                            is RelaySessionOutput.EndOfStoredEvents -> {
                                if (output.subscriptionId == subscriptionId) {
                                    eoseReceived.complete(Unit)
                                }
                            }
                            is RelaySessionOutput.SubscriptionTerminated -> {
                                if (output.subscriptionId == subscriptionId) {
                                    eoseReceived.completeExceptionally(
                                        SubscriptionRejectedException(output.reason, output.code)
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }

                subscribe(subscriptionId, filters)

                val completed = withTimeoutOrNull(timeoutMillis) {
                    eoseReceived.await()
                    true
                } ?: false

                collectorJob.cancelAndJoin()
                unsubscribe(subscriptionId)

                if (completed) {
                    QueryResult.Success(events.toList())
                } else {
                    QueryResult.Timeout(events.toList())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubscriptionRejectedException) {
            QueryResult.ConnectionFailed("Subscription rejected: ${e.reason}")
        } catch (e: Exception) {
            QueryResult.ConnectionFailed(e.message)
        }
    }
}
