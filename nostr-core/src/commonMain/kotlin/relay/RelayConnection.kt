package nostr.core.relay

/**
 * Callback invoked when a frame write completes (or fails) at the transport level.
 *
 * @param success true if the frame was successfully written to the socket
 * @param cause the exception if the write failed, null on success
 */
typealias WriteConfirmationCallback = (success: Boolean, cause: Throwable?) -> Unit

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
     *
     * Note: [RelaySendResult.Accepted] only means the frame was queued for sending, not that
     * it was actually written to the socket. For write confirmation, use [sendWithConfirmation].
     */
    fun send(frame: String): RelaySendResult

    /**
     * Attempts to send a client frame with write confirmation.
     *
     * Like [send], but invokes [onWritten] when the frame is actually written to the underlying
     * socket (or fails to write). This enables detection of stale/dead connections that appear
     * connected but can't actually deliver messages.
     *
     * Default implementation falls back to [send] and immediately confirms success if accepted.
     * Transport implementations that support true write confirmation should override this.
     *
     * @param frame the frame to send
     * @param onWritten callback invoked when write completes or fails
     * @return the send result (whether frame was accepted for sending)
     */
    fun sendWithConfirmation(frame: String, onWritten: WriteConfirmationCallback): RelaySendResult {
        val result = send(frame)
        // Default: confirm immediately based on queue acceptance (no true write confirmation)
        when (result) {
            RelaySendResult.Accepted -> onWritten(true, null)
            RelaySendResult.NotConnected -> onWritten(false, IllegalStateException("Not connected"))
            is RelaySendResult.Failed -> onWritten(false, result.cause)
        }
        return result
    }

    /**
     * Initiates a graceful shutdown of the active websocket connection.
     * Implementations should ignore the call when not connected.
     */
    fun close(code: Int = RelayCloseReason.Normal.code, reason: String? = null)
}