package io.github.nicolals.nostr.core.transport

import okio.ByteString

/**
 * Represents a single WebSocket connection to a Nostr relay.
 *
 * ## Lifecycle
 *
 * 1. Create a connection via [RelayTransportFactory.create]
 * 2. Set a listener via [setListener]
 * 3. Call [connect] to initiate the WebSocket handshake
 * 4. On success, [RelayConnectionListener.onOpen] is called
 * 5. Send messages via [send], receive via listener
 * 6. Call [close] to initiate close handshake, or handle remote close
 * 7. [RelayConnectionListener.onClosed] is called when fully terminated
 * 8. Clear listener with `setListener(null)` to avoid leaks
 *
 * ## Thread Safety
 *
 * - [state] can be read from any thread
 * - [connect], [send], [ping], [close] should be called from a single thread
 *   (typically the caller's main/dispatch thread)
 * - Listener callbacks are invoked from the transport's internal thread
 *
 * ## Error Handling
 *
 * Send operations return [TransportResult] rather than throwing exceptions.
 * Check [TransportResult.isSuccess] or pattern match on the result to
 * handle errors.
 *
 * ## Connection Reuse
 *
 * A [RelayConnection] instance is single-use. After [close] is called or
 * the connection fails, create a new connection to reconnect.
 */
interface RelayConnection {

    /**
     * The relay URL this connection is for.
     */
    val url: RelayUrl

    /**
     * The current connection state.
     *
     * This can be read from any thread and always reflects the current state.
     */
    val state: TransportState

    /**
     * Sets the listener to receive connection events.
     *
     * Should be called before [connect]. Pass `null` to clear the listener
     * (important for avoiding memory leaks).
     *
     * @param listener The listener to receive events, or null to clear.
     */
    fun setListener(listener: RelayConnectionListener?)

    /**
     * Initiates the WebSocket connection.
     *
     * This is non-blocking. Connection completion is signaled via
     * [RelayConnectionListener.onOpen] (success) or
     * [RelayConnectionListener.onFailure] (failure).
     *
     * Can only be called once per connection instance, and only when
     * in [TransportState.Idle] state.
     *
     * @throws IllegalStateException if not in [TransportState.Idle] state.
     */
    fun connect()

    /**
     * Sends a text message to the relay.
     *
     * @param text The message to send.
     * @return [TransportResult.Success] if the message was queued for sending,
     *   or [TransportResult.Failure] if sending failed (e.g., not connected).
     */
    fun send(text: String): TransportResult<Unit>

    /**
     * Sends a WebSocket ping frame.
     *
     * The response is delivered via [RelayConnectionListener.onPong].
     *
     * @param payload Optional payload to include in the ping.
     * @return [TransportResult.Success] if the ping was sent,
     *   or [TransportResult.Failure] if sending failed.
     */
    fun ping(payload: ByteString? = null): TransportResult<Unit>

    /**
     * Initiates a graceful close of the WebSocket connection.
     *
     * This sends a close frame to the relay and waits for the close
     * handshake to complete. The connection is not immediately closed;
     * [RelayConnectionListener.onClosing] and [RelayConnectionListener.onClosed]
     * will be called in sequence.
     *
     * This method is idempotent - calling it multiple times has no
     * additional effect.
     *
     * @param code The WebSocket close code. Default is 1000 (normal closure).
     * @param reason Optional human-readable close reason.
     */
    fun close(code: Int = CloseInfo.NORMAL_CLOSURE, reason: String? = null)
}
