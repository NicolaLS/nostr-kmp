package io.github.nicolals.nostr.core.transport

import okio.ByteString

/**
 * Callback interface for receiving events from a [RelayConnection].
 *
 * ## Threading
 *
 * Callbacks are invoked **serially** from the transport's internal thread.
 * Implementations must not block in callback methods. If processing requires
 * significant work, dispatch to another thread or queue.
 *
 * ## Event Ordering
 *
 * Events are delivered in order. For a successful connection lifecycle:
 * 1. [onOpen] - Connection established
 * 2. [onMessage]/[onPong] - Zero or more data events
 * 3. [onClosing] - Close handshake started (optional, may go straight to onClosed)
 * 4. [onClosed] - Connection terminated
 *
 * For failed connections:
 * 1. [onFailure] - Error occurred
 * 2. [onClosed] - Connection terminated
 *
 * ## Lifecycle
 *
 * Set a listener via [RelayConnection.setListener] before calling
 * [RelayConnection.connect]. Clear the listener with `setListener(null)`
 * when done to avoid leaks.
 */
interface RelayConnectionListener {

    /**
     * Called when the WebSocket connection is successfully established.
     *
     * After this callback, [RelayConnection.send] can be used.
     */
    fun onOpen()

    /**
     * Called when a text message is received from the relay.
     *
     * @param text The message content.
     */
    fun onMessage(text: String)

    /**
     * Called when a pong frame is received in response to a ping.
     *
     * @param payload The pong payload, if any. May be null.
     */
    fun onPong(payload: ByteString?)

    /**
     * Called when the WebSocket close handshake has been initiated.
     *
     * This may be called when the remote peer sends a close frame, or
     * after the local side calls [RelayConnection.close]. The connection
     * is still active but draining; [onClosed] will follow.
     *
     * @param info Details about the close (code, reason, who initiated).
     */
    fun onClosing(info: CloseInfo)

    /**
     * Called when the WebSocket connection has been fully closed.
     *
     * This is always the final callback. After this, the connection
     * cannot be reused.
     *
     * @param info Details about the close (code, reason, who initiated).
     */
    fun onClosed(info: CloseInfo)

    /**
     * Called when a transport error occurs.
     *
     * After a failure, [onClosed] will be called with an appropriate
     * close code. The connection transitions to [TransportState.Failed]
     * then [TransportState.Closed].
     *
     * @param error The transport error that occurred.
     */
    fun onFailure(error: TransportError)
}

/**
 * An empty [RelayConnectionListener] implementation that does nothing.
 *
 * Useful as a base class when you only need to handle specific events.
 */
open class RelayConnectionListenerAdapter : RelayConnectionListener {
    override fun onOpen() {}
    override fun onMessage(text: String) {}
    override fun onPong(payload: ByteString?) {}
    override fun onClosing(info: CloseInfo) {}
    override fun onClosed(info: CloseInfo) {}
    override fun onFailure(error: TransportError) {}
}
