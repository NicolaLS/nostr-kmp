package nostr.core.relay

/**
 * Contract for a low-level connection to a Nostr relay.
 *
 * Implementations are expected to speak the Nostr websocket protocol and deliver textual frames.
 * The lifecycle is driven through callbacks to avoid imposing a particular concurrency toolkit.
 */
interface RelayConnection {
    /** Relay endpoint URL, e.g. `wss://relay.example.org`. */
    val url: String

    /**
     * Initiates the connection and associates the provided [listener] with this instance.
     *
     * Implementations should invoke [RelayConnectionListener.onOpen] once the websocket handshake
     * completes, and then stream incoming frames through [RelayConnectionListener.onMessage].
     * Failures that occur before or after the connection opens must be reported via
     * [RelayConnectionListener.onFailure].
     */
    fun connect(listener: RelayConnectionListener)

    /**
     * Attempts to send a client frame to the relay.
     *
     * Returns [RelaySendResult.Accepted] when the frame was handed off to the transport layer,
     * [RelaySendResult.NotConnected] if no session is currently open, or [RelaySendResult.Failed]
     * when the transport rejects the frame synchronously.
     */
    fun send(frame: String): RelaySendResult

    /**
     * Initiates a graceful shutdown of the active websocket connection.
     * Implementations should ignore the call when not connected.
     */
    fun close(code: Int = RelayCloseReason.Normal.code, reason: String? = null)
}