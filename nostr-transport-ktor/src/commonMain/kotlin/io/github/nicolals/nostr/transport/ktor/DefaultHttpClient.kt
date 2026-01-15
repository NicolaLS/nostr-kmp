package io.github.nicolals.nostr.transport.ktor

import io.ktor.client.HttpClient

/**
 * Creates a platform-specific [HttpClient] configured for WebSocket connections.
 *
 * The returned client uses the appropriate engine for the platform:
 * - JVM: OkHttp
 * - Android: OkHttp
 * - iOS: Darwin
 *
 * The client has the WebSockets plugin installed and is ready to use with
 * [KtorRelayTransportFactory].
 *
 * ## Usage
 *
 * ```kotlin
 * val client = createDefaultHttpClient()
 * val factory = KtorRelayTransportFactory(client)
 * ```
 *
 * @return A new [HttpClient] configured for WebSocket connections.
 */
expect fun createDefaultHttpClient(): HttpClient
