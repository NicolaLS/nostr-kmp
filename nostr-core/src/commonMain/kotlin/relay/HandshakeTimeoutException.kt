package nostr.core.relay

/** Raised when a relay websocket handshake exceeds the configured timeout. */
class HandshakeTimeoutException(
    val relayUrl: String,
    val timeoutMillis: Long
) : Exception("Handshake timeout after ${timeoutMillis}ms for $relayUrl")
