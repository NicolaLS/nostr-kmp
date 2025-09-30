package nostr.core.relay

/** Listener that receives lifecycle and message events from a [RelayConnection]. */
interface RelayConnectionListener {

    /** Signals that the websocket handshake succeeded and the connection is ready for use. */
    fun onOpen(connection: RelayConnection)

    /** Delivers a textual frame received from the relay. */
    fun onMessage(message: String)

    /**
     * Indicates that the connection closed. Implementations should treat this as terminal; a new
     * connection must be created for any subsequent work.
     */
    fun onClosed(code: Int, reason: String?)

    /** Reports an unrecoverable transport failure. */
    fun onFailure(cause: Throwable)
}
