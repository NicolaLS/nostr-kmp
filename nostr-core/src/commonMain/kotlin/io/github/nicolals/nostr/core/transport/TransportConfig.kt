package io.github.nicolals.nostr.core.transport

/**
 * Configuration options for a WebSocket transport connection.
 *
 * These are low-level transport settings only. Policy decisions like
 * retry behavior, keepalive intervals, and reconnection logic are
 * handled by higher-level runtime components.
 *
 * @property connectTimeoutMillis Maximum time to wait for the WebSocket
 *   handshake to complete. Default is 10 seconds.
 * @property maxFrameSizeBytes Maximum size of a single WebSocket frame.
 *   Messages larger than this will cause a protocol error. Default is 16 MB.
 * @property headers Additional HTTP headers to include in the WebSocket
 *   upgrade request (e.g., for authentication).
 * @property subprotocols WebSocket subprotocols to request during handshake.
 *   Empty by default (no subprotocol negotiation).
 */
data class TransportConfig(
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val maxFrameSizeBytes: Long = DEFAULT_MAX_FRAME_SIZE_BYTES,
    val headers: Map<String, String> = emptyMap(),
    val subprotocols: List<String> = emptyList(),
) {
    init {
        require(connectTimeoutMillis > 0) {
            "connectTimeoutMillis must be positive, got: $connectTimeoutMillis"
        }
        require(maxFrameSizeBytes > 0) {
            "maxFrameSizeBytes must be positive, got: $maxFrameSizeBytes"
        }
    }

    companion object {
        /** Default connection timeout: 10 seconds. */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L

        /** Default maximum frame size: 16 MB. */
        const val DEFAULT_MAX_FRAME_SIZE_BYTES = 16L * 1024 * 1024
    }
}
