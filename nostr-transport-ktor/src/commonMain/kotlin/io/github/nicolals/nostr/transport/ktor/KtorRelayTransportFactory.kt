package io.github.nicolals.nostr.transport.ktor

import io.github.nicolals.nostr.core.transport.RelayConnection
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.ktor.client.HttpClient

/**
 * Factory for creating Ktor-based [RelayConnection] instances.
 *
 * ## Usage
 *
 * ```kotlin
 * // Create HttpClient with WebSockets plugin
 * val client = HttpClient(OkHttp) {
 *     install(WebSockets)
 * }
 *
 * // Create factory
 * val factory = KtorRelayTransportFactory(client)
 *
 * // Create connection
 * val connection = factory.create(RelayUrl.parse("wss://relay.example.com"))
 * connection.setListener(myListener)
 * connection.connect()
 * ```
 *
 * ## HttpClient Requirements
 *
 * The provided [HttpClient] must have the WebSockets plugin installed.
 * The engine should be appropriate for the target platform:
 * - JVM/Android: OkHttp
 * - iOS: Darwin
 *
 * ## Thread Safety
 *
 * This factory is thread-safe. [create] can be called from any thread.
 * The HttpClient must also be thread-safe (which it is by default).
 *
 * @param httpClient The Ktor HTTP client with WebSocket support installed.
 */
class KtorRelayTransportFactory(
    private val httpClient: HttpClient,
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
