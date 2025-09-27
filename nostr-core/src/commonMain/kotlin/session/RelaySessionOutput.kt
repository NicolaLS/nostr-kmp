package nostr.core.session

import nostr.core.model.Event
import nostr.core.model.PublishResult
import nostr.core.model.SubscriptionId

/**
 * Notifications emitted by the reducer. Runtimes should surface these to application code
 * so clients know about connection changes, subscription lifecycle events, inbound relay
 * messages, and publish acknowledgements.
 */
sealed interface RelaySessionOutput {
    /** Connection snapshot changed (opened, closing, failed, etc.). */
    data class ConnectionStateChanged(val snapshot: ConnectionSnapshot) : RelaySessionOutput

    /** A subscription was successfully registered with the engine. */
    data class SubscriptionRegistered(val subscriptionId: SubscriptionId) : RelaySessionOutput

    /**
     * Relay sent a `CLOSED` frame to terminate the subscription.
     * @param subscriptionId identifier associated with the subscription.
     * @param reason human-readable explanation emitted by the relay (never `null`).
     * @param code optional machine-readable prefix extracted from the relay response.
     */
    data class SubscriptionTerminated(
        val subscriptionId: SubscriptionId,
        val reason: String,
        val code: String?
    ) : RelaySessionOutput

    /** Relay delivered an event for the given subscription. */
    data class EventReceived(val subscriptionId: SubscriptionId, val event: Event) : RelaySessionOutput

    /** Relay signaled the end of stored events for the subscription. */
    data class EndOfStoredEvents(val subscriptionId: SubscriptionId) : RelaySessionOutput

    /** Relay sent a `NOTICE` frame. */
    data class Notice(val message: String) : RelaySessionOutput

    /** Relay responded to an `EVENT` publish with an `OK` frame. */
    data class PublishAcknowledged(val result: PublishResult) : RelaySessionOutput

    /** Relay requested NIP-42 authentication. */
    data class AuthChallenge(val challenge: String) : RelaySessionOutput

    /** Relay responded to a `COUNT` request. */
    data class CountResult(val subscriptionId: SubscriptionId, val count: Int) : RelaySessionOutput

    /** The engine encountered an error it could not resolve autonomously. */
    data class Error(val error: EngineError) : RelaySessionOutput
}

/** Categorises engine-level errors so clients can react appropriately. */
sealed interface EngineError {
    data class ConnectionFailure(val url: String?, val message: String) : EngineError
    data class ProtocolViolation(val description: String) : EngineError
    data class OutboundFailure(val reason: String) : EngineError
}

/** Snapshot of the connection state maintained inside [RelaySessionState]. */
sealed interface ConnectionSnapshot {
    object Disconnected : ConnectionSnapshot
    data class Connecting(val url: String) : ConnectionSnapshot
    data class Connected(val url: String) : ConnectionSnapshot
    data class Disconnecting(val url: String, val code: Int?, val reason: String?) : ConnectionSnapshot
    data class Failed(val url: String?, val message: String) : ConnectionSnapshot
}
