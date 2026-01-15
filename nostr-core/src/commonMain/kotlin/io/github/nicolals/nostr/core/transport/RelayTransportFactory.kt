package io.github.nicolals.nostr.core.transport

/**
 * Factory for creating [RelayConnection] instances.
 *
 * Implementations provide the actual WebSocket transport (e.g., Ktor, OkHttp).
 * This allows consumers to bring their own WebSocket stack.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val factory: RelayTransportFactory = KtorRelayTransportFactory(httpClient)
 *
 * val connection = factory.create(RelayUrl.parse("wss://relay.example.com"))
 * connection.setListener(myListener)
 * connection.connect()
 * ```
 *
 * ## Thread Safety
 *
 * Factory implementations should be thread-safe. [create] can be called
 * from any thread.
 */
fun interface RelayTransportFactory {

    /**
     * Creates a new [RelayConnection] for the given relay URL.
     *
     * The returned connection is in [TransportState.Idle] state.
     * Call [RelayConnection.connect] to initiate the connection.
     *
     * @param url The relay URL to connect to.
     * @param config Transport configuration options.
     * @return A new connection instance ready to be connected.
     */
    fun create(url: RelayUrl, config: TransportConfig): RelayConnection

    /**
     * Creates a new [RelayConnection] for the given relay URL with default configuration.
     *
     * @param url The relay URL to connect to.
     * @return A new connection instance ready to be connected.
     */
    fun create(url: RelayUrl): RelayConnection = create(url, TransportConfig())
}
