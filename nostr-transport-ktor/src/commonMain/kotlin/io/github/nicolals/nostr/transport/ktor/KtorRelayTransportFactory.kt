package io.github.nicolals.nostr.transport.ktor

import io.github.nicolals.nostr.core.transport.RelayConnection
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Factory for creating Ktor-based [RelayConnection] instances.
 *
 * ## Quick Start
 *
 * ```kotlin
 * // Create factory with default platform HttpClient (recommended)
 * val factory = KtorRelayTransportFactory()
 *
 * // Create connection
 * val connection = factory.create(RelayUrl.parse("wss://relay.example.com"))
 * connection.setListener(myListener)
 * connection.connect()
 * ```
 *
 * ## Custom HttpClient
 *
 * For advanced use cases (custom timeouts, interceptors, etc.), provide your own client:
 *
 * ```kotlin
 * val client = HttpClient(CIO) {
 *     install(WebSockets)
 *     // Custom configuration...
 * }
 * val factory = KtorRelayTransportFactory(client)
 * ```
 *
 * Supplied engines must support cancellation of unfinished WebSocket upgrades.
 * Ktor 3.4's OkHttp engine can keep its native request alive until its own timeout
 * after coroutine cancellation; the default JVM/Android CIO client avoids that
 * limitation. The caller owns configuration and cleanup of a supplied client.
 *
 * ## Platform Engines
 *
 * The default client uses the appropriate engine for each platform:
 * - JVM/Android: CIO
 * - iOS: Darwin
 *
 * ## Thread Safety
 *
 * This factory is thread-safe. [create] can be called from any thread.
 *
 * Default construction owns its HTTP client; construction with a supplied client borrows it.
 */
class KtorRelayTransportFactory private constructor(
    private val httpClient: HttpClient,
    private val ownsClient: Boolean,
) : RelayTransportFactory, AutoCloseable {
    /** Creates and owns a platform HTTP client. Close this factory when done. */
    constructor() : this(createDefaultHttpClient(), ownsClient = true)

    /** Borrows a client; the caller remains responsible for closing it. */
    constructor(httpClient: HttpClient) : this(httpClient, ownsClient = false)

    private data class State(
        val closed: Boolean = false,
        val connections: Set<KtorRelayConnection> = emptySet(),
    )

    private val state = MutableStateFlow(State())

    override fun create(url: RelayUrl, config: TransportConfig): RelayConnection {
        lateinit var connection: KtorRelayConnection
        connection = KtorRelayConnection(httpClient, url, config, onClosed = {
            state.update { it.copy(connections = it.connections - connection) }
        })
        while (true) {
            val current = state.value
            if (current.closed) {
                connection.close()
                error("Transport factory is closed")
            }
            if (state.compareAndSet(current, current.copy(connections = current.connections + connection))) {
                return connection
            }
        }
    }

    /** Closes all connections and any HTTP client created by this factory. */
    override fun close() {
        while (true) {
            val current = state.value
            if (current.closed) return
            if (state.compareAndSet(current, current.copy(closed = true))) {
                current.connections.forEach { it.close() }
                if (ownsClient) httpClient.close()
                return
            }
        }
    }

    /** Waits for connection cleanup, and for the owned client's shutdown. */
    suspend fun closeAndJoin() {
        close()
        state.value.connections.forEach { it.awaitClosed() }
        if (ownsClient) httpClient.coroutineContext[Job]?.join()
    }
}
