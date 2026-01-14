package io.github.nicolals.nostr.nip01.message

import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.view.RelayMessageView
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * NIP-01 RELAY EVENT message - relay sends an event matching a subscription.
 * Wire format: ["EVENT", <subscription_id>, <event JSON>]
 */
data class RelayEventMessage(
    override val raw: RawRelayMessage,
    /** The subscription ID as raw string */
    val subscriptionIdString: String,
    /** The domain-typed subscription ID (validated), null if validation failed */
    val subscriptionId: SubscriptionId?,
    /** The wire-format event (permissive) */
    val wireEvent: WireEvent,
    /** The domain-typed event (validated), null if validation failed */
    val event: Event?,
) : RelayMessageView {
    companion object {
        const val TOKEN = "EVENT"
    }
}

/**
 * NIP-01 RELAY OK message - relay acknowledges event submission.
 * Wire format: ["OK", <event_id>, <true|false>, <message>]
 */
data class OkMessage(
    override val raw: RawRelayMessage,
    /** The event ID as raw hex string */
    val eventIdHex: String,
    /** The domain-typed event ID (validated), null if validation failed */
    val eventId: EventId?,
    /** Whether the event was accepted */
    val accepted: Boolean,
    /** Human-readable message (may include machine-readable prefix) */
    val message: String,
) : RelayMessageView {
    companion object {
        const val TOKEN = "OK"
    }
}

/**
 * NIP-01 RELAY EOSE message - end of stored events for a subscription.
 * Wire format: ["EOSE", <subscription_id>]
 */
data class EoseMessage(
    override val raw: RawRelayMessage,
    /** The subscription ID as raw string */
    val subscriptionIdString: String,
    /** The domain-typed subscription ID (validated), null if validation failed */
    val subscriptionId: SubscriptionId?,
) : RelayMessageView {
    companion object {
        const val TOKEN = "EOSE"
    }
}

/**
 * NIP-01 RELAY CLOSED message - subscription was closed by the relay.
 * Wire format: ["CLOSED", <subscription_id>, <message>]
 */
data class ClosedMessage(
    override val raw: RawRelayMessage,
    /** The subscription ID as raw string */
    val subscriptionIdString: String,
    /** The domain-typed subscription ID (validated), null if validation failed */
    val subscriptionId: SubscriptionId?,
    /** Human-readable reason (may include machine-readable prefix) */
    val message: String,
) : RelayMessageView {
    companion object {
        const val TOKEN = "CLOSED"
    }
}

/**
 * NIP-01 RELAY NOTICE message - human-readable notice from the relay.
 * Wire format: ["NOTICE", <message>]
 */
data class NoticeMessage(
    override val raw: RawRelayMessage,
    /** Human-readable message */
    val message: String,
) : RelayMessageView {
    companion object {
        const val TOKEN = "NOTICE"
    }
}
