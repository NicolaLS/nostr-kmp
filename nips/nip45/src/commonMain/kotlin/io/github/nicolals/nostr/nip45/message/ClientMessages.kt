package io.github.nicolals.nostr.nip45.message

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * NIP-45 CLIENT COUNT message - requests event counts matching filters.
 * Wire format: ["COUNT", <subscription_id>, <filter JSON>...]
 *
 * The relay will respond with a COUNT relay message containing the count,
 * or a CLOSED message if it refuses to fulfill the request.
 */
data class CountClientMessage(
    override val raw: RawClientMessage,
    /** The subscription/query ID as raw string */
    val subscriptionIdString: String,
    /** The domain-typed subscription ID (validated), null if validation failed */
    val subscriptionId: SubscriptionId?,
    /** The wire-format filters (permissive) */
    val wireFilters: List<WireFilter>,
    /** The domain-typed filters (validated), null if any validation failed */
    val filters: List<Filter>?,
) : ClientMessageView {
    companion object {
        const val TOKEN = "COUNT"

        /**
         * Create a COUNT client message from domain types.
         */
        fun create(
            subscriptionId: SubscriptionId,
            filters: List<Filter>,
            codec: NostrWireCodec
        ): CountClientMessage {
            val wireFilters = filters.map { it.toWireFilter() }
            val array = JsonArray(buildList {
                add(JsonString(TOKEN))
                add(JsonString(subscriptionId.value))
                wireFilters.forEach { add(codec.encodeFilter(it)) }
            })
            return CountClientMessage(
                RawClientMessage(array),
                subscriptionId.value,
                subscriptionId,
                wireFilters,
                filters
            )
        }
    }
}
