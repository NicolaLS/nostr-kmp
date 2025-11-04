package nostr.runtime.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.core.codec.WireDecoder
import nostr.core.codec.WireEncoder
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.session.DefaultRelaySessionReducer
import nostr.core.session.RelaySessionReducer
import nostr.core.session.RelaySessionSettings
import nostr.core.session.RelaySessionState
import nostr.core.relay.RelayConnectionFactory

/**
 * High-level lifecycle manager that shares [CoroutineNostrRuntime] instances per relay URL. It owns the coroutine
 * scopes required by each runtime, handles reference counting, and offers ergonomic helpers for acquiring,
 * releasing, and temporarily using sessions.
 *
 * This abstraction centralises runtime ownership so library consumers avoid duplicating scope plumbing and shutdown
 * logic across call sites.
 */
class RelaySessionManager(
    scope: CoroutineScope,
    private val connectionFactory: RelayConnectionFactory,
    private val wireEncoder: WireEncoder,
    private val wireDecoder: WireDecoder,
    private val sessionSettings: RelaySessionSettings = RelaySessionSettings(),
    private val sessionReducer: RelaySessionReducer = DefaultRelaySessionReducer(),
    private val initialState: RelaySessionState = RelaySessionState()
) {

    private val supervisorJob: Job = SupervisorJob(scope.coroutineContext[Job])
    private val parentScope: CoroutineScope = scope + supervisorJob
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, ManagedRelaySession>()

    /**
     * Acquire a managed session for [url], creating the shared runtime if necessary. Call [ManagedRelaySession.release]
     * when the caller no longer needs to interact with the relay.
     */
    suspend fun acquire(url: String): ManagedRelaySession {
        val session = mutex.withLock {
            sessions[url] ?: createSession(url).also { sessions[url] = it }
        }
        session.retain()
        return session
    }

    /**
     * Acquire a session lazily, execute [block], and release the session afterwards regardless of success.
     */
    suspend fun <T> use(url: String, block: suspend ManagedRelaySession.() -> T): T {
        val session = acquire(url)
        return try {
            block(session)
        } finally {
            session.release()
        }
    }

    /**
     * Collapse all managed sessions and cancel the supervisor scope. Intended for application shutdown or tests;
     * acquiring sessions after invoking this function requires a new manager instance.
     */
    suspend fun shutdown() {
        val active = mutex.withLock {
            val current = sessions.values.toList()
            sessions.clear()
            current
        }
        active.forEach { it.forceShutdown() }
        supervisorJob.cancel()
    }

    private suspend fun releaseSession(session: ManagedRelaySession) {
        mutex.withLock {
            val current = sessions[session.url]
            if (current === session) {
                sessions.remove(session.url)
            }
        }
        session.shutdown()
    }

    private fun createSession(url: String): ManagedRelaySession {
        val childScope = parentScope + SupervisorJob(parentScope.coroutineContext[Job])
        val runtime = CoroutineNostrRuntime(
            scope = childScope,
            connectionFactory = connectionFactory,
            wireEncoder = wireEncoder,
            wireDecoder = wireDecoder,
            settings = sessionSettings,
            reducer = sessionReducer,
            initialState = initialState
        )
        return ManagedRelaySession(url, runtime, childScope) { session ->
            releaseSession(session)
        }
    }

    /**
     * Wrapper around [CoroutineNostrRuntime] with reference counting semantics. Consumers can drive the runtime via
     * strongly-typed helpers without reaching into the underlying implementation.
     */
    class ManagedRelaySession internal constructor(
        val url: String,
        internal val runtime: CoroutineNostrRuntime,
        private val scope: CoroutineScope,
        private val releaseAction: suspend (ManagedRelaySession) -> Unit
    ) {
        private val refMutex = Mutex()
        private var references: Int = 0
        private var closed = false

        internal suspend fun retain() {
            refMutex.withLock {
                check(!closed) { "ManagedRelaySession for $url already released" }
                references += 1
            }
        }

        /**
         * Release the caller's reference to this session. When the final reference drops, the runtime shuts down.
         */
        suspend fun release() {
            var shouldClose = false
            var skip = false
            refMutex.withLock {
                if (closed || references == 0) {
                    skip = true
                    return@withLock
                }
                references -= 1
                if (references == 0) {
                    closed = true
                    shouldClose = true
                }
            }
            if (skip) return
            if (shouldClose) {
                releaseAction(this)
            }
        }

        /** Establish a connection to the managed relay. */
        suspend fun connect() = runtime.connect(url)

        /** Disconnect from the relay with an optional websocket close code and reason. */
        suspend fun disconnect(code: Int? = 1000, reason: String? = null) = runtime.disconnect(code, reason)

        /** Subscribe to filters using either generated or explicit subscription identifiers. */
        suspend fun subscribe(subscriptionId: SubscriptionId, filters: List<Filter>) =
            runtime.subscribe(subscriptionId, filters)

        suspend fun subscribe(subscriptionId: String, filters: List<Filter>) =
            runtime.subscribe(subscriptionId, filters)

        /** Cancel an existing subscription. */
        suspend fun unsubscribe(subscriptionId: SubscriptionId) = runtime.unsubscribe(subscriptionId)

        suspend fun unsubscribe(subscriptionId: String) = runtime.unsubscribe(subscriptionId)

        /** Publish an event through the managed runtime. */
        suspend fun publish(event: Event) = runtime.publish(event)

        /** Respond to a NIP-42 authentication challenge. */
        suspend fun authenticate(event: Event) = runtime.authenticate(event)

        /** Expose underlying state and output streams for observers. */
        val connectionSnapshots = runtime.connectionSnapshots
        val connectionTelemetry = runtime.connectionTelemetry
        val states = runtime.states
        val outputs = runtime.outputs

        internal suspend fun shutdown() {
            runtime.shutdown()
            (scope.coroutineContext[Job])?.cancel()
        }

        internal suspend fun forceShutdown() {
            refMutex.withLock {
                closed = true
                references = 0
            }
            shutdown()
        }
    }
}
