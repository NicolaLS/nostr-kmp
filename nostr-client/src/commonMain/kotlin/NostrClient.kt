package io.github.nicolals.nostr.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.core.codec.WireDecoder
import nostr.core.codec.WireEncoder
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.relay.RelayConnectionFactory
import nostr.core.session.DefaultRelaySessionReducer
import nostr.core.session.Nip42AuthState
import nostr.core.session.RelaySessionReducer
import nostr.core.session.RelaySessionSettings
import nostr.core.session.RelaySessionState
import nostr.runtime.coroutines.RelaySessionManager
import nostr.runtime.coroutines.RelaySessionManager.ManagedRelaySession

class NostrClient constructor(
    private val scope: CoroutineScope,
    private val connectionFactory: RelayConnectionFactory,
    private val wireEncoder: WireEncoder,
    private val wireDecoder: WireDecoder,
    private val authProvider: Nip42AuthProvider,
    private val sessionSettings: RelaySessionSettings = RelaySessionSettings(),
    private val sessionReducer: RelaySessionReducer = DefaultRelaySessionReducer(sessionSettings),
    private val initialState: RelaySessionState = RelaySessionState()
) {
    private val manager = RelaySessionManager(
        scope = scope,
        connectionFactory = connectionFactory,
        wireEncoder = wireEncoder,
        wireDecoder = wireDecoder,
        sessionSettings = sessionSettings,
        sessionReducer = sessionReducer,
        initialState = initialState
    )

    private data class AuthHandle(val authenticator: Nip42AutoAuthenticator, var references: Int)

    private val authMutex = Mutex()
    private val authenticators = mutableMapOf<ManagedRelaySession, AuthHandle>()

    suspend fun acquire(url: String): NostrClientSession {
        val session = manager.acquire(url)
        val authenticator = setupAuthenticator(session)
        return NostrClientSession(session, authenticator) { toRelease ->
            releaseSession(toRelease)
        }
    }

    suspend fun <T> use(url: String, block: suspend NostrClientSession.() -> T): T {
        val session = acquire(url)
        return try {
            block(session)
        } finally {
            session.release()
        }
    }

    suspend fun shutdown() {
        val toStop: List<Nip42AutoAuthenticator> = authMutex.withLock {
            val handles = authenticators.values.map { it.authenticator }
            authenticators.clear()
            handles
        }
        toStop.forEach { runCatching { it.stop() } }
        manager.shutdown()
    }

    private suspend fun setupAuthenticator(session: ManagedRelaySession): Nip42AutoAuthenticator {
        return authMutex.withLock {
            val existing = authenticators[session]
            if (existing != null) {
                existing.references += 1
                return@withLock existing.authenticator
            }
            val handle = Nip42AutoAuthenticator(scope, ManagedSessionHandle(session), authProvider)
            authenticators[session] = AuthHandle(handle, references = 1)
            handle
        }
    }

    private suspend fun releaseSession(session: ManagedRelaySession) {
        val toStop: Nip42AutoAuthenticator? = authMutex.withLock {
            val handle = authenticators[session] ?: return@withLock null
            handle.references -= 1
            if (handle.references <= 0) {
                authenticators.remove(session)
                handle.authenticator
            } else {
                null
            }
        }
        toStop?.let { runCatching { it.stop() } }
        session.release()
    }
}

class NostrClientSession internal constructor(
    private val delegate: ManagedRelaySession,
    private val authenticator: Nip42AutoAuthenticator,
    private val releaseAction: suspend (ManagedRelaySession) -> Unit
) {
    private val releaseMutex = Mutex()
    private var released = false

    val url: String get() = delegate.url
    val outputs = delegate.outputs
    val connectionSnapshots = delegate.connectionSnapshots
    val connectionTelemetry = delegate.connectionTelemetry
    val states = delegate.states
    val authStates: StateFlow<Nip42AuthState> = authenticator.authStates
    val autoAuthStatuses: StateFlow<NostrClientAutoAuthStatus> = authenticator.statuses

    suspend fun connect() = delegate.connect()
    suspend fun disconnect(code: Int? = 1000, reason: String? = null) = delegate.disconnect(code, reason)

    suspend fun subscribe(subscriptionId: SubscriptionId, filters: List<Filter>) =
        delegate.subscribe(subscriptionId, filters)

    suspend fun subscribe(subscriptionId: String, filters: List<Filter>) =
        delegate.subscribe(subscriptionId, filters)

    suspend fun unsubscribe(subscriptionId: SubscriptionId) = delegate.unsubscribe(subscriptionId)
    suspend fun unsubscribe(subscriptionId: String) = delegate.unsubscribe(subscriptionId)

    suspend fun publish(event: Event) = delegate.publish(event)

    suspend fun authenticate(event: Event) = delegate.authenticate(event)

    suspend fun release() {
        val shouldRelease = releaseMutex.withLock {
            if (released) {
                false
            } else {
                released = true
                true
            }
        }
        if (shouldRelease) {
            releaseAction(delegate)
        }
    }
}
