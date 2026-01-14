package io.github.nicolals.nostr.nip01.message

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.codec.wire.toWireEvent
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * NIP-01 CLIENT EVENT message - publishes an event to the relay.
 * Wire format: ["EVENT", <event JSON>]
 */
data class EventClientMessage(
    override val raw: RawClientMessage,
    /** The wire-format event (permissive) */
    val wireEvent: WireEvent,
    /** The domain-typed event (validated), null if validation failed */
    val event: Event?,
) : ClientMessageView {
    companion object {
        const val TOKEN = "EVENT"

        /**
         * Create an EVENT client message from a domain Event.
         */
        fun create(event: Event, codec: NostrWireCodec): EventClientMessage {
            val wireEvent = event.toWireEvent()
            val array = JsonArray(listOf(JsonString(TOKEN), codec.encodeEvent(wireEvent)))
            return EventClientMessage(RawClientMessage(array), wireEvent, event)
        }
    }
}

/**
 * NIP-01 CLIENT REQ message - subscribes to events matching filters.
 * Wire format: ["REQ", <subscription_id>, <filter JSON>...]
 */
data class ReqClientMessage(
    override val raw: RawClientMessage,
    /** The subscription ID as raw string */
    val subscriptionIdString: String,
    /** The domain-typed subscription ID (validated), null if validation failed */
    val subscriptionId: SubscriptionId?,
    /** The wire-format filters (permissive) */
    val wireFilters: List<WireFilter>,
    /** The domain-typed filters (validated), null if any validation failed */
    val filters: List<Filter>?,
) : ClientMessageView {
    companion object {
        const val TOKEN = "REQ"

        /**
         * Create a REQ client message from domain types.
         */
        fun create(
            subscriptionId: SubscriptionId,
            filters: List<Filter>,
            codec: NostrWireCodec
        ): ReqClientMessage {
            val wireFilters = filters.map { it.toWireFilter() }
            val array = JsonArray(buildList {
                add(JsonString(TOKEN))
                add(JsonString(subscriptionId.value))
                wireFilters.forEach { add(codec.encodeFilter(it)) }
            })
            return ReqClientMessage(
                RawClientMessage(array),
                subscriptionId.value,
                subscriptionId,
                wireFilters,
                filters
            )
        }
    }
}

/**
 * NIP-01 CLIENT CLOSE message - unsubscribes from a subscription.
 * Wire format: ["CLOSE", <subscription_id>]
 */
data class CloseClientMessage(
    override val raw: RawClientMessage,
    /** The subscription ID as raw string */
    val subscriptionIdString: String,
    /** The domain-typed subscription ID (validated), null if validation failed */
    val subscriptionId: SubscriptionId?,
) : ClientMessageView {
    companion object {
        const val TOKEN = "CLOSE"

        /**
         * Create a CLOSE client message from a domain SubscriptionId.
         */
        fun create(subscriptionId: SubscriptionId): CloseClientMessage {
            val array = JsonArray(listOf(JsonString(TOKEN), JsonString(subscriptionId.value)))
            return CloseClientMessage(RawClientMessage(array), subscriptionId.value, subscriptionId)
        }
    }
}
