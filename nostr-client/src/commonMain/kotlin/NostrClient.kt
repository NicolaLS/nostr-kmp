package io.github.nicolals.nostr.client

import io.github.nicolals.nostr.client.internal.SubscriptionPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.crypto.Signer
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.RelaySessionOutput
import nostr.crypto.KeyPair
import nostr.runtime.coroutines.EagerRetryConfig
import nostr.runtime.coroutines.RelaySessionManager
import nostr.runtime.coroutines.RelaySessionManager.ManagedRelaySession
import nostr.runtime.coroutines.RequestResult
import nostr.runtime.coroutines.SmartRelaySession
import nostr.transport.ktor.KtorRelayConnectionFactory
import nostr.crypto.Signer as CryptoSigner

/**
 * High-level Nostr client with automatic connection management,
 * NIP-42 authentication, and request-response patterns.
 *
 * ## Quick Start
 *
 * ```kotlin
 * val client = NostrClient.create(signer)
 *
 * // Add relays for default operations
 * client.addRelay("wss://relay.damus.io")
 * client.addRelay("wss://nos.lol")
 *
 * // Publish to all added relays
 * val handle = client.publish(event)
 *
 * // Or publish to specific relay + all added relays
 * val handle = client.publish("wss://relay.nostr.band", event)
 *
 * // Optionally wait for acknowledgments
 * val results = handle.awaitAll(timeoutMillis = 5000)
 *
 * // Query from all added relays
 * val events = client.query(filters)
 *
 * // Subscribe to all added relays
 * client.subscribe(filters).collect { event -> ... }
 *
 * // Clean up
 * client.shutdown()
 * ```
 */
class NostrClient private constructor(
    private val config: NostrClientConfig,
    private val scope: CoroutineScope,
    private val signerProvider: SignerProvider
) {
    private val codec = KotlinxSerializationWireCodec.default()
    private val connectionFactory = KtorRelayConnectionFactory(scope)

    private val manager = RelaySessionManager(
        scope = scope,
        connectionFactory = connectionFactory,
        wireEncoder = codec,
        wireDecoder = codec
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, RelayHandle>()
    private val managedRelays = mutableSetOf<String>()

    private data class RelayHandle(
        val session: ManagedRelaySession,
        val smart: SmartRelaySession,
        val pool: SubscriptionPool,
        val authenticator: Nip42AutoAuthenticator,
        val connectionState: MutableStateFlow<ConnectionState>,
        val okListenerJob: Job
    )

    // Cache the public key for non-ephemeral mode
    private val cachedPublicKey: String? = if (!config.ephemeralKeys) {
        signerProvider.getSigner().publicKey.hex
    } else {
        null
    }

    // ==========================================================================
    // Public API - Identity
    // ==========================================================================

    /**
     * The public key (hex-encoded) used by this client.
     *
     * @throws IllegalStateException if called on an ephemeral client
     */
    val publicKey: String
        get() = cachedPublicKey
            ?: throw IllegalStateException("Ephemeral clients have no stable public key")

    /**
     * The public key (hex-encoded) if available, or null for ephemeral clients.
     */
    val publicKeyOrNull: String?
        get() = cachedPublicKey

    /**
     * Whether this client uses ephemeral (per-publish) keys.
     */
    val isEphemeral: Boolean
        get() = config.ephemeralKeys

    // ==========================================================================
    // Public API - Relay Management
    // ==========================================================================

    /**
     * List of currently managed relay URLs.
     */
    val relays: Set<String>
        get() = managedRelays.toSet()

    /**
     * Add a relay to the managed set.
     *
     * Managed relays are automatically used for operations when no specific
     * URL is provided, or in addition to any explicitly specified URL.
     *
     * @param url Relay WebSocket URL
     */
    suspend fun addRelay(url: String) {
        val normalizedUrl = normalizeUrl(url)
        mutex.withLock {
            managedRelays.add(normalizedUrl)
        }
        // Pre-create the handle to establish connection
        getOrCreateHandle(normalizedUrl)
    }

    /**
     * Remove a relay from the managed set.
     *
     * This closes any subscriptions and connections to the relay.
     *
     * @param url Relay WebSocket URL
     */
    suspend fun removeRelay(url: String) {
        val normalizedUrl = normalizeUrl(url)
        val handle: RelayHandle?
        mutex.withLock {
            managedRelays.remove(normalizedUrl)
            handle = sessions.remove(normalizedUrl)
        }
        handle?.let { cleanupHandle(it) }
    }

    /**
     * Force a relay transport reconnection.
     *
     * Useful on iOS lifecycle/network transitions where WebSocket sessions can become half-open without
     * surfacing a clean close/error.
     */
    suspend fun invalidateRelay(url: String, code: Int? = 1000, reason: String? = "Invalidated") {
        val normalizedUrl = normalizeUrl(url)
        manager.invalidate(normalizedUrl, code = code, reason = reason)
    }

    /**
     * Force transport reconnection for all known relay sessions.
     */
    suspend fun invalidateAllRelays(code: Int? = 1000, reason: String? = "Invalidated") {
        manager.invalidateAll(code = code, reason = reason)
    }

    // ==========================================================================
    // Public API - Query
    // ==========================================================================

    /**
     * Query events from all managed relays.
     *
     * Results are deduplicated by event ID.
     *
     * @param filters Nostr filters to match events
     * @param timeoutMillis Maximum time for the operation
     * @return Combined deduplicated events from all managed relays
     */
    suspend fun query(
        filters: List<Filter>,
        timeoutMillis: Long = config.defaultTimeoutMillis
    ): RequestResult<List<Event>> {
        val urls = mutex.withLock { managedRelays.toList() }
        if (urls.isEmpty()) {
            return RequestResult.ConnectionFailed(0, "No relays configured. Call addRelay() first.")
        }
        return query(urls, filters, timeoutMillis)
    }

    /**
     * Query events from a specific relay (plus all managed relays).
     *
     * Results are deduplicated by event ID.
     *
     * @param url Additional relay URL to query
     * @param filters Nostr filters to match events
     * @param timeoutMillis Maximum time for the operation
     * @return Combined deduplicated events from all relays
     */
    suspend fun query(
        url: String,
        filters: List<Filter>,
        timeoutMillis: Long = config.defaultTimeoutMillis
    ): RequestResult<List<Event>> {
        val urls = mutex.withLock { (managedRelays + url).toList() }
        return query(urls, filters, timeoutMillis)
    }

    /**
     * Query events from specific relays only.
     *
     * Results are deduplicated by event ID.
     *
     * @param urls Relay WebSocket URLs to query
     * @param filters Nostr filters to match events
     * @param timeoutMillis Maximum time for the operation
     * @return Combined deduplicated events from specified relays
     */
    suspend fun query(
        urls: List<String>,
        filters: List<Filter>,
        timeoutMillis: Long = config.defaultTimeoutMillis
    ): RequestResult<List<Event>> = coroutineScope {
        val results = urls.map { url ->
            async { querySingleRelay(url, filters, timeoutMillis) }
        }.awaitAll()

        // Combine successful results, deduplicate by event ID
        val allEvents = mutableMapOf<String, Event>()
        var lastFailure: RequestResult<List<Event>>? = null

        for (result in results) {
            when (result) {
                is RequestResult.Success -> {
                    result.value.forEach { event ->
                        allEvents[event.id] = event
                    }
                }
                else -> lastFailure = result
            }
        }

        if (allEvents.isNotEmpty()) {
            RequestResult.Success(allEvents.values.toList())
        } else {
            lastFailure ?: RequestResult.Success(emptyList())
        }
    }

    private suspend fun querySingleRelay(
        url: String,
        filters: List<Filter>,
        timeoutMillis: Long
    ): RequestResult<List<Event>> {
        val handle = getOrCreateHandle(url)
        return handle.smart.query(filters, timeoutMillis, retryConfig())
    }

    // ==========================================================================
    // Public API - Publish
    // ==========================================================================

    /**
     * Publish an event to all managed relays.
     *
     * Returns a [PublishHandle] that can be used to track acknowledgments.
     * The handle can be ignored for fire-and-forget publishing.
     *
     * @param event Event to publish (must be signed)
     * @return Handle for tracking relay acknowledgments
     */
    suspend fun publish(event: Event): PublishHandle {
        val urls = mutex.withLock { managedRelays.toList() }
        require(urls.isNotEmpty()) { "No relays configured. Call addRelay() first." }
        return publishToRelays(urls, event)
    }

    /**
     * Publish an event to a specific relay plus all managed relays.
     *
     * Returns a [PublishHandle] that can be used to track acknowledgments.
     *
     * @param url Additional relay URL to publish to
     * @param event Event to publish (must be signed)
     * @return Handle for tracking relay acknowledgments
     */
    suspend fun publish(url: String, event: Event): PublishHandle {
        val urls = mutex.withLock { (managedRelays + url).toList() }
        return publishToRelays(urls, event)
    }

    /**
     * Publish an event to specific relays only.
     *
     * Returns a [PublishHandle] that can be used to track acknowledgments.
     *
     * @param urls Relay WebSocket URLs to publish to
     * @param event Event to publish (must be signed)
     * @return Handle for tracking relay acknowledgments
     */
    suspend fun publish(urls: List<String>, event: Event): PublishHandle {
        return publishToRelays(urls, event)
    }

    private suspend fun publishToRelays(urls: List<String>, event: Event): PublishHandle {
        val handle = PublishHandle(event.id, urls)

        coroutineScope {
            urls.forEach { url ->
                launch {
                    val relayHandle = getOrCreateHandle(url)

                    // Ensure connected before publishing
                    relayHandle.smart.connect()

                    // Publish the event
                    relayHandle.session.publish(event)
                }
            }
        }

        return handle
    }

    // ==========================================================================
    // Public API - Request (Request-Response Pattern)
    // ==========================================================================

    /**
     * Send a request event and wait for a correlated response.
     *
     * Uses the first managed relay. For NWC and similar protocols.
     * Internally uses shared subscription pools for efficiency.
     *
     * @param request Request event to publish
     * @param responseFilter Filter to match the expected response
     * @param timeoutMillis Maximum time for the operation
     * @return Success with response event, or failure result
     */
    suspend fun request(
        request: Event,
        responseFilter: Filter,
        timeoutMillis: Long = config.defaultTimeoutMillis
    ): RequestResult<Event> {
        val url = mutex.withLock { managedRelays.firstOrNull() }
            ?: return RequestResult.ConnectionFailed(0, "No relays configured. Call addRelay() first.")
        return request(url, request, responseFilter, timeoutMillis)
    }

    /**
     * Send a request event and wait for a correlated response from a specific relay.
     *
     * Internally uses shared subscription pools for efficiency.
     *
     * @param url Relay WebSocket URL
     * @param request Request event to publish
     * @param responseFilter Filter to match the expected response
     * @param timeoutMillis Maximum time for the operation
     * @return Success with response event, or failure result
     */
    suspend fun request(
        url: String,
        request: Event,
        responseFilter: Filter,
        timeoutMillis: Long = config.defaultTimeoutMillis
    ): RequestResult<Event> {
        val handle = getOrCreateHandle(url)
        val subscription = handle.pool.getOrCreate(responseFilter)
        return handle.smart.requestOneVia(
            subscription = subscription,
            requestEvent = request,
            correlationId = request.id,
            timeoutMillis = timeoutMillis,
            retryConfig = retryConfig()
        )
    }

    // ==========================================================================
    // Public API - Subscribe
    // ==========================================================================

    /**
     * Subscribe to events from all managed relays.
     *
     * Events are deduplicated by ID.
     *
     * @param filters Nostr filters for the subscription
     * @return Flow of deduplicated events from all managed relays
     */
    fun subscribe(filters: List<Filter>): Flow<Event> {
        return flow {
            val urls = mutex.withLock { managedRelays.toList() }
            if (urls.isEmpty()) {
                throw IllegalStateException("No relays configured. Call addRelay() first.")
            }
            merge(*urls.map { subscribeSingleRelay(it, filters) }.toTypedArray())
                .distinctUntilChanged { old, new -> old.id == new.id }
                .collect { emit(it) }
        }
    }

    /**
     * Subscribe to events from a specific relay plus all managed relays.
     *
     * Events are deduplicated by ID.
     *
     * @param url Additional relay URL to subscribe to
     * @param filters Nostr filters for the subscription
     * @return Flow of deduplicated events
     */
    fun subscribe(url: String, filters: List<Filter>): Flow<Event> {
        return flow {
            val urls = mutex.withLock { (managedRelays + url).toList() }
            merge(*urls.map { subscribeSingleRelay(it, filters) }.toTypedArray())
                .distinctUntilChanged { old, new -> old.id == new.id }
                .collect { emit(it) }
        }
    }

    /**
     * Subscribe to events from specific relays only.
     *
     * Events are deduplicated by ID.
     *
     * @param urls Relay WebSocket URLs to subscribe to
     * @param filters Nostr filters for the subscription
     * @return Flow of deduplicated events
     */
    fun subscribe(urls: List<String>, filters: List<Filter>): Flow<Event> {
        val flows = urls.map { subscribeSingleRelay(it, filters) }
        return merge(*flows.toTypedArray())
            .distinctUntilChanged { old, new -> old.id == new.id }
    }

    private fun subscribeSingleRelay(url: String, filters: List<Filter>): Flow<Event> {
        return flow {
            val handle = getOrCreateHandle(url)
            val subscriptionId = nostr.runtime.coroutines.SharedSubscription.generateId("sub")

            handle.smart.connect()
            handle.smart.subscribe(subscriptionId, filters)

            try {
                handle.session.outputs
                    .collect { output ->
                        when (output) {
                            is RelaySessionOutput.EventReceived -> {
                                if (output.subscriptionId == subscriptionId) {
                                    emit(output.event)
                                }
                            }
                            else -> {}
                        }
                    }
            } finally {
                runCatching { handle.smart.unsubscribe(subscriptionId) }
            }
        }
    }

    // ==========================================================================
    // Public API - Connection State
    // ==========================================================================

    /**
     * Get the connection state for a relay.
     *
     * @param url Relay WebSocket URL
     * @return StateFlow of connection state
     */
    suspend fun connectionState(url: String): StateFlow<ConnectionState> {
        val handle = getOrCreateHandle(url)
        return handle.connectionState.asStateFlow()
    }

    // ==========================================================================
    // Public API - Lifecycle
    // ==========================================================================

    /**
     * Shutdown the client, closing all connections.
     *
     * The client cannot be reused after shutdown.
     */
    suspend fun shutdown() {
        val toClose: List<RelayHandle>
        mutex.withLock {
            toClose = sessions.values.toList()
            sessions.clear()
            managedRelays.clear()
        }
        toClose.forEach { cleanupHandle(it) }
        manager.shutdown()
    }

    // ==========================================================================
    // Internal Helpers
    // ==========================================================================

    private suspend fun cleanupHandle(handle: RelayHandle) {
        runCatching { handle.okListenerJob.cancel() }
        runCatching { handle.pool.close() }
        runCatching { handle.authenticator.stop() }
    }

    private fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
    }

    private val publishHandles = mutableMapOf<String, MutableList<PublishHandle>>()
    private val publishHandlesMutex = Mutex()

    private suspend fun getOrCreateHandle(url: String): RelayHandle {
        return mutex.withLock {
            sessions.getOrPut(url) { createHandle(url) }
        }
    }

    private suspend fun createHandle(url: String): RelayHandle {
        val session = manager.acquire(url)
        val smart = session.smart
        val pool = SubscriptionPool(smart)

        val signer = signerProvider.getSigner()
        val authProvider = signer.asNip42AuthProvider()
        val authenticator = Nip42AutoAuthenticator(scope, ManagedSessionHandle(session), authProvider)

        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

        // Monitor connection state
        scope.launch {
            session.connectionSnapshots.collect { snapshot ->
                connectionState.value = when (snapshot) {
                    is ConnectionSnapshot.Disconnected -> ConnectionState.Disconnected
                    is ConnectionSnapshot.Connecting -> ConnectionState.Connecting
                    is ConnectionSnapshot.Connected -> ConnectionState.Connected
                    is ConnectionSnapshot.Disconnecting -> ConnectionState.Disconnected
                    is ConnectionSnapshot.Failed -> ConnectionState.Failed(snapshot.message)
                }
            }
        }

        // Listen for OK responses to route to PublishHandles
        val okListenerJob = scope.launch {
            session.outputs.collect { output ->
                when (output) {
                    is RelaySessionOutput.PublishAcknowledged -> {
                        val result = output.result
                        publishHandlesMutex.withLock {
                            publishHandles[result.eventId]?.forEach { handle ->
                                handle.onResult(url, result)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        return RelayHandle(session, smart, pool, authenticator, connectionState, okListenerJob)
    }

    internal suspend fun registerPublishHandle(handle: PublishHandle) {
        publishHandlesMutex.withLock {
            publishHandles.getOrPut(handle.eventId) { mutableListOf() }.add(handle)
        }
    }

    private fun retryConfig(): EagerRetryConfig {
        return EagerRetryConfig(
            maxRetries = config.maxRetries,
            eagerConnectTimeoutMillis = config.connectTimeoutMillis
        )
    }

    // ==========================================================================
    // Signer Provider
    // ==========================================================================

    private fun interface SignerProvider {
        fun getSigner(): Signer
    }

    // ==========================================================================
    // Factory Methods
    // ==========================================================================

    companion object {
        /**
         * Create a client with an existing signer.
         *
         * @param signer The signer for authentication and publishing
         */
        fun create(signer: Signer): NostrClient {
            val config = NostrClientConfig(signer = signer)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            return NostrClient(config, scope) { signer }
        }

        /**
         * Create a client that generates its own keypair.
         *
         * Useful for quick prototyping or anonymous usage.
         * The same keypair is used for all operations.
         */
        fun create(): NostrClient {
            val keyPair = KeyPair.random()
            val signer = CryptoSigner.fromKeyPair(keyPair)
            return create(signer)
        }

        /**
         * Create a client that uses a new random keypair for every publish.
         *
         * Provides maximum privacy by preventing event correlation.
         * Each publish operation will use a different identity.
         */
        fun ephemeral(): NostrClient {
            val config = NostrClientConfig(ephemeralKeys = true)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            return NostrClient(config, scope) {
                val keyPair = KeyPair.random()
                CryptoSigner.fromKeyPair(keyPair)
            }
        }

        /**
         * Create a client with custom configuration.
         *
         * @param config Custom configuration
         */
        fun create(config: NostrClientConfig): NostrClient {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val signerProvider: SignerProvider = if (config.ephemeralKeys) {
                SignerProvider {
                    val keyPair = KeyPair.random()
                    CryptoSigner.fromKeyPair(keyPair)
                }
            } else {
                val signer = config.signer ?: run {
                    val keyPair = KeyPair.random()
                    CryptoSigner.fromKeyPair(keyPair)
                }
                SignerProvider { signer }
            }
            return NostrClient(config, scope, signerProvider)
        }
    }
}
