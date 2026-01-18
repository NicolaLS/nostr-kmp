package io.github.nicolals.nostr.transport.ktor

import io.github.nicolals.nostr.core.transport.RelayConnection
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.ktor.client.HttpClient

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
 * val client = HttpClient(OkHttp) {
 *     install(WebSockets)
 *     // Custom configuration...
 * }
 * val factory = KtorRelayTransportFactory(client)
 * ```
 *
 * ## Platform Engines
 *
 * The default client uses the appropriate engine for each platform:
 * - JVM/Android: OkHttp
 * - iOS: Darwin
 *
 * ## Thread Safety
 *
 * This factory is thread-safe. [create] can be called from any thread.
 *
 * @param httpClient The Ktor HTTP client with WebSocket support. Defaults to [createDefaultHttpClient].
 */
class KtorRelayTransportFactory(
    private val httpClient: HttpClient = createDefaultHttpClient(),
) : RelayTransportFactory {

    /**
     * Creates a new [RelayConnection] for the given relay URL.
     *
     * The returned connection is in [io.github.nicolals.nostr.core.transport.TransportState.Idle] state.
     * Call [RelayConnection.connect] to initiate the connection.
     *
     * @param url The relay URL to connect to.
     * @param config Transport configuration options.
     * @return A new Ktor-based connection instance ready to be connected.
     */
    override fun create(url: RelayUrl, config: TransportConfig): RelayConnection {
        return KtorRelayConnection(httpClient, url, config)
    }
}
