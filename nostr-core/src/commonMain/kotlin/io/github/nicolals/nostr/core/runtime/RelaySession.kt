package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.transport.RelayUrl

/**
 * A session to a single Nostr relay.
 *
 * The session is the primary API surface for interacting with a relay.
 * It manages the connection lifecycle, drives the protocol engine,
 * and applies configured policies (reconnect, retry, deduplication).
 *
 * ## Lifecycle
 *
 * 1. Create a session via a factory or builder (implementation-specific)
 * 2. Observe [connectionStatus] to track connection state
 * 3. Call [connect] to establish the connection
 * 4. Use operations like subscribe/publish (implementation-specific)
 * 5. Call [disconnect] to close the connection
 *
 * ## Policy Application
 *
 * The session applies policies based on configuration:
 * - **Reconnect policy**: Determines how to handle disconnects
 * - **Retry policy**: Determines how to handle interrupted operations
 * - **Deduplication policy**: Filters duplicate events
 *
 * ## Thread Safety
 *
 * Implementations must be thread-safe. The session can be used from
 * multiple threads/coroutines concurrently.
 *
 * ## Custom Implementations
 *
 * This interface defines the minimal contract for a session. Runtime
 * implementations extend this with concrete subscribe/publish methods
 * that return appropriate types for their concurrency model:
 *
 * - Coroutines: `Flow<Event>`, `suspend fun`
 * - Callbacks: Callback interfaces
 * - Reactive: Observable/Flowable
 *
 * @see SessionConfig for configuration options
 */
interface RelaySession {
    /**
     * The relay URL this session connects to.
     */
    val url: RelayUrl

    /**
     * The current connection status.
     *
     * Implementations should make this observable (e.g., StateFlow for
     * coroutines, Observable for Rx, callbacks for callback-based).
     */
    val connectionStatus: ConnectionStatus

    /**
     * Initiates connection to the relay.
     *
     * If already connected or connecting, this is a no-op.
     * If in [ConnectionStatus.Failed] state, this resets and attempts
     * a fresh connection.
     *
     * Connection progress and completion are signaled via [connectionStatus].
     */
    fun connect()

    /**
     * Disconnects from the relay.
     *
     * Closes the WebSocket connection and cancels any pending operations.
     * Active subscriptions are interrupted (not closed gracefully).
     *
     * After disconnect, the session can be reconnected by calling [connect].
     *
     * @param code WebSocket close code. Default is 1000 (normal closure).
     * @param reason Optional close reason.
     */
    fun disconnect(code: Int = 1000, reason: String? = null)

    /**
     * Closes the session and releases all resources.
     *
     * After close, the session cannot be reconnected. This is a terminal
     * operation.
     *
     * Implementations should:
     * - Disconnect if connected
     * - Cancel all pending operations
     * - Clear deduplication cache
     * - Release any held resources
     */
    fun close()
}
