package io.github.nicolals.nostr.transport.ktor

import io.github.nicolals.nostr.core.transport.TransportConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

actual fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets) {
        maxFrameSize = TransportConfig.DEFAULT_MAX_FRAME_SIZE_BYTES
    }
}
