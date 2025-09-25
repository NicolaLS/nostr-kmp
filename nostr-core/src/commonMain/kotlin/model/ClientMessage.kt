package nostr.core.model

/**
 * Client-to-relay messages defined by NIP-01. These correspond directly to the JSON arrays
 * sent over the WebSocket connection.
 */
sealed interface ClientMessage {
    /** `EVENT` frame carrying a signed [event]. */
    data class Event(val event: nostr.core.model.Event) : ClientMessage

    /** `REQ` frame registering a subscription identified by [subscriptionId]. */
    data class Req(val subscriptionId: SubscriptionId, val filters: List<Filter>) : ClientMessage

    /** `CLOSE` frame removing a subscription. */
    data class Close(val subscriptionId: SubscriptionId) : ClientMessage

    /** `AUTH` frame containing a signed authentication event (NIP-42). */
    data class Auth(val event: nostr.core.model.Event) : ClientMessage
}
