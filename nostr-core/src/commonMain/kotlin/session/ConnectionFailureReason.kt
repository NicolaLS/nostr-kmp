package nostr.core.session

/**
 * High-level classification for connection failures surfaced by the session reducer.
 *
 * @property description brief human-readable label primarily for debugging.
 */
enum class ConnectionFailureReason(val description: String) {
    /** Transport factory failed to provision a connection instance. */
    ConnectionFactory("connection_factory"),

    /** Transport opened but the relay rejected or terminated the handshake. */
    OpenHandshake("open_handshake"),

    /** Streaming frames from the relay failed due to IO or protocol defects. */
    StreamFailure("stream_failure"),

    /** Runtime could not determine the failure cause but propagates the message. */
    Unknown("unknown")
}
