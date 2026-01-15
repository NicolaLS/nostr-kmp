package io.github.nicolals.nostr.core.transport

/**
 * Represents transport-level errors that can occur during WebSocket communication.
 *
 * These errors are transport-specific and do not include application-level
 * protocol errors (like invalid Nostr messages).
 */
sealed class TransportError {

    /**
     * A network I/O error occurred (e.g., connection refused, network unreachable,
     * read/write failure).
     *
     * @property message Description of the I/O error.
     * @property cause The underlying exception, if available.
     */
    data class IoError(
        val message: String,
        val cause: Throwable? = null,
    ) : TransportError()

    /**
     * A WebSocket protocol error occurred (e.g., invalid frame, invalid UTF-8
     * in text frame, unexpected opcode).
     *
     * @property message Description of the protocol violation.
     */
    data class ProtocolError(
        val message: String,
    ) : TransportError()

    /**
     * A TLS/SSL error occurred during the secure connection handshake
     * (e.g., certificate validation failure, handshake timeout).
     *
     * @property message Description of the TLS error.
     * @property cause The underlying exception, if available.
     */
    data class TlsError(
        val message: String,
        val cause: Throwable? = null,
    ) : TransportError()

    /**
     * The connection attempt timed out before the WebSocket handshake completed.
     *
     * @property timeoutMillis The configured timeout that was exceeded.
     */
    data class ConnectionTimeout(
        val timeoutMillis: Long,
    ) : TransportError()

    /**
     * The WebSocket was closed unexpectedly by the remote peer without
     * a proper close handshake.
     *
     * This typically indicates the remote server crashed, the network
     * connection was lost, or the connection was forcibly terminated.
     */
    data object UnexpectedClosure : TransportError()

    /**
     * An operation was attempted in an invalid state (e.g., sending
     * on a closed connection).
     *
     * @property operation The operation that was attempted.
     * @property currentState The state the connection was in.
     */
    data class InvalidState(
        val operation: String,
        val currentState: TransportState,
    ) : TransportError()
}
