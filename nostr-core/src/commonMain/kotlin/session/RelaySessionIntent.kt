package nostr.core.session

import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.RelayMessage
import nostr.core.model.SubscriptionId

/**
 * Inputs fed into the session reducer. Intents describe everything that can happen to a
 * client: user-driven actions (connect, subscribe, publish) as well as network or runtime
 * notifications (frames received, connections closing, send failures).
 */
sealed interface RelaySessionIntent {
    /** Request establishing a WebSocket connection to [url]. */
    data class Connect(val url: String) : RelaySessionIntent

    /** Request closing the current connection with an optional close [code]/[reason]. */
    data class Disconnect(val code: Int? = 1000, val reason: String? = null) : RelaySessionIntent

    /** Register a subscription identified by [subscriptionId] with the given [filters]. */
    data class Subscribe(val subscriptionId: SubscriptionId, val filters: List<Filter>) : RelaySessionIntent

    /** Remove a previously registered subscription. */
    data class Unsubscribe(val subscriptionId: SubscriptionId) : RelaySessionIntent

    /** Enqueue or immediately send a signed [event]. */
    data class Publish(val event: Event) : RelaySessionIntent

    /** Indicates that the transport opened a WebSocket at [url]. */
    data class ConnectionEstablished(val url: String) : RelaySessionIntent

    /** Transport-level closure notification with the relay-provided [code] and [reason]. */
    data class ConnectionClosed(val url: String, val code: Int, val reason: String?) : RelaySessionIntent

    /** Transport failed to open or maintain the connection. */
    data class ConnectionFailed(
        val url: String?,
        val reason: ConnectionFailureReason,
        val message: String,
        val closeCode: Int? = null,
        val closeReason: String? = null,
        val cause: String? = null
    ) : RelaySessionIntent

    /** Raw relay message received from the active connection. */
    data class RelayEvent(val message: RelayMessage) : RelaySessionIntent

    /** Sending a frame failed; allows the reducer to surface an error to the client. */
    data class OutboundFailure(val attemptedCommand: RelaySessionCommand.SendToRelay, val reason: String) :
        RelaySessionIntent
}
