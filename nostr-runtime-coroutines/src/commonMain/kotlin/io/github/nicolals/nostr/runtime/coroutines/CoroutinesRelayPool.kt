package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.runtime.ConnectionStatus
import io.github.nicolals.nostr.core.runtime.DeduplicationPolicy
import io.github.nicolals.nostr.core.runtime.SessionConfig
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Relay role for pooled sessions.
 */
enum class RelayRole {
    Read,
    Write,
    ReadWrite;

    val canRead: Boolean
        get() = this == Read || this == ReadWrite

    val canWrite: Boolean
        get() = this == Write || this == ReadWrite
}

/**
 * Coroutines-based relay pool managing multiple sessions.
 */
class CoroutinesRelayPool(
    private val moduleContext: NipModuleContext,
    private val transportFactory: RelayTransportFactory,
    private val scope: CoroutineScope,
    private val sessionConfig: SessionConfig = SessionConfig.Default,
    private val deduplicationPolicyFactory: () -> DeduplicationPolicy = { DeduplicationPolicy.lru() },
    private val additionalModules: List<NipModule> = emptyList(),
) {
    private data class RelayEntry(
        val session: CoroutinesRelaySession,
        var role: RelayRole,
        val jobs: MutableList<Job> = mutableListOf(),
    )

    private val mutex = Mutex()
    private val relays = mutableMapOf<RelayUrl, RelayEntry>()

    private val _connectionStatuses = MutableStateFlow<Map<RelayUrl, ConnectionStatus>>(emptyMap())
    val connectionStatusFlow: StateFlow<Map<RelayUrl, ConnectionStatus>> = _connectionStatuses.asStateFlow()

    private val _notices = MutableSharedFlow<RelayNotice>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notices: SharedFlow<RelayNotice> = _notices.asSharedFlow()

    private val _errors = MutableSharedFlow<SessionError>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<SessionError> = _errors.asSharedFlow()

    /**
     * Adds a relay to the pool or updates its role if already present.
     */
    suspend fun addRelay(url: RelayUrl, role: RelayRole = RelayRole.ReadWrite): CoroutinesRelaySession {
        val entry = mutex.withLock {
            val existing = relays[url]
            if (existing != null) {
                existing.role = role
                return existing.session
            }

            val session = CoroutinesRelaySession(
                url = url,
                moduleContext = moduleContext,
                transportFactory = transportFactory,
                scope = scope,
                config = sessionConfig,
                additionalModules = additionalModules,
            )
            val newEntry = RelayEntry(session, role)
            relays[url] = newEntry
            _connectionStatuses.update { current ->
                current + (url to session.connectionStatus)
            }
            attachSession(newEntry)
            newEntry
        }

        return entry.session
    }

    /**
     * Removes a relay from the pool.
     */
    suspend fun removeRelay(url: RelayUrl, close: Boolean = true) {
        val entry = mutex.withLock {
            relays.remove(url)?.also {
                _connectionStatuses.update { current -> current - url }
            }
        } ?: return

        entry.jobs.forEach { it.cancel() }
        if (close) {
            entry.session.closeAndJoin()
        }
    }

    /**
     * Updates the role for an existing relay.
     */
    suspend fun updateRole(url: RelayUrl, role: RelayRole) {
        mutex.withLock {
            relays[url]?.role = role
        }
    }

    /**
     * Connects all relays in the pool.
     */
    suspend fun connectAll() {
        val sessions = mutex.withLock { relays.values.map { it.session } }
        sessions.forEach { it.connect() }
    }

    /**
     * Disconnects all relays in the pool.
     */
    suspend fun disconnectAll() {
        val sessions = mutex.withLock { relays.values.map { it.session } }
        sessions.forEach { it.disconnect() }
    }

    /**
     * Closes all sessions in the pool.
     */
    suspend fun close() {
        val entries = mutex.withLock {
            val current = relays.values.toList()
            relays.clear()
            _connectionStatuses.value = emptyMap()
            current
        }

        entries.forEach { entry ->
            entry.jobs.forEach { it.cancel() }
            entry.session.closeAndJoin()
        }
    }

    /**
     * Creates a pooled subscription across selected relays.
     *
     * @param filters Filters defining which events to receive.
     * @param subscriptionId Optional subscription ID. Defaults to a random ID.
     * @param relayUrls Optional explicit relay set. Defaults to all readable relays.
     */
    suspend fun subscribe(
        filters: List<Filter>,
        subscriptionId: SubscriptionId = generateSubscriptionId(),
        relayUrls: Set<RelayUrl>? = null,
    ): CoroutinesPoolSubscriptionHandle {
        val entries = selectRelays(relayUrls) { it.canRead }

        val handles = entries.mapValues { (_, entry) ->
            entry.session.subscribe(filters, subscriptionId)
        }

        return CoroutinesPoolSubscriptionHandle(
            subscriptionId = subscriptionId,
            handles = handles,
            deduplicationPolicy = deduplicationPolicyFactory(),
            scope = scope,
        )
    }

    /**
     * Publishes an event to selected relays.
     *
     * @param event The signed event to publish.
     * @param relayUrls Optional explicit relay set. Defaults to all writable relays.
     */
    suspend fun publish(
        event: Event,
        relayUrls: Set<RelayUrl>? = null,
    ): CoroutinesPoolPublishHandle {
        val entries = selectRelays(relayUrls) { it.canWrite }

        val handles = entries.mapValues { (_, entry) ->
            entry.session.publish(event)
        }

        return CoroutinesPoolPublishHandle(
            eventId = event.id,
            handles = handles,
            scope = scope,
        )
    }

    private suspend fun selectRelays(
        relayUrls: Set<RelayUrl>?,
        roleFilter: (RelayRole) -> Boolean,
    ): Map<RelayUrl, RelayEntry> = mutex.withLock {
        if (relayUrls == null) {
            relays.filter { (_, entry) -> roleFilter(entry.role) }
        } else {
            relays.filter { (url, entry) ->
                relayUrls.contains(url) && roleFilter(entry.role)
            }
        }
    }

    private fun attachSession(entry: RelayEntry) {
        val session = entry.session

        val noticeJob = scope.launch {
            session.notices.collect { notice ->
                _notices.emit(notice)
            }
        }
        val errorJob = scope.launch {
            session.errors.collect { error ->
                _errors.emit(error)
            }
        }
        val statusJob = scope.launch {
            session.connectionStatusFlow.collect { status ->
                mutex.withLock {
                    if (relays.containsKey(session.url)) {
                        _connectionStatuses.update { current ->
                            current + (session.url to status)
                        }
                    }
                }
            }
        }

        entry.jobs.add(noticeJob)
        entry.jobs.add(errorJob)
        entry.jobs.add(statusJob)
    }

    private fun generateSubscriptionId(): SubscriptionId {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val id = (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return SubscriptionId(id)
    }
}
