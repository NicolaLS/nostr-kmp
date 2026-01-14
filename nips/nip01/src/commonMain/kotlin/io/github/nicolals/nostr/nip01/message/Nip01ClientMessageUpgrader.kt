package io.github.nicolals.nostr.nip01.message

import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.codec.wire.toEventOrNull
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.view.ClientMessageView

/**
 * Upgrader for NIP-01 client messages: EVENT, REQ, CLOSE.
 */
class Nip01ClientMessageUpgrader(
    private val wireCodec: NostrWireCodec,
) : ClientMessageUpgrader<ClientMessageView> {

    override val tokens: Set<String> = setOf(
        EventClientMessage.TOKEN,
        ReqClientMessage.TOKEN,
        CloseClientMessage.TOKEN,
    )

    override fun upgradeOrNull(raw: RawClientMessage, token: String): ClientMessageView? {
        return when (token) {
            EventClientMessage.TOKEN -> upgradeEventMessage(raw)
            ReqClientMessage.TOKEN -> upgradeReqMessage(raw)
            CloseClientMessage.TOKEN -> upgradeCloseMessage(raw)
            else -> null
        }
    }

    private fun upgradeEventMessage(raw: RawClientMessage): EventClientMessage? {
        // ["EVENT", <event>]
        if (raw.payloadSize != 1) return null
        val wireEvent = runCatching { wireCodec.decodeEvent(raw.payloadAt(0)) }.getOrNull()
            ?: return null
        val event = wireEvent.toEventOrNull()
        return EventClientMessage(raw, wireEvent, event)
    }

    private fun upgradeReqMessage(raw: RawClientMessage): ReqClientMessage? {
        // ["REQ", <subscription_id>, <filter>...]
        val filterCount = raw.payloadSize - 1
        if (filterCount < 1) return null

        val subscriptionIdString = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null
        val subscriptionId = subscriptionIdString.toSubscriptionIdOrNull()

        val wireFilters = mutableListOf<WireFilter>()
        for (i in 0 until filterCount) {
            val filterValue = raw.payloadAt(i + 1)
            val wireFilter = runCatching { wireCodec.decodeFilter(filterValue) }.getOrNull()
                ?: return null
            wireFilters.add(wireFilter)
        }

        val filters = wireFilters.map { it.toFilterOrNull() }
        val allFiltersValid = filters.all { it != null }

        return ReqClientMessage(
            raw = raw,
            subscriptionIdString = subscriptionIdString,
            subscriptionId = subscriptionId,
            wireFilters = wireFilters,
            filters = if (allFiltersValid) filters.filterNotNull() else null,
        )
    }

    private fun upgradeCloseMessage(raw: RawClientMessage): CloseClientMessage? {
        // ["CLOSE", <subscription_id>]
        if (raw.payloadSize != 1) return null

        val subscriptionIdString = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null
        val subscriptionId = subscriptionIdString.toSubscriptionIdOrNull()

        return CloseClientMessage(raw, subscriptionIdString, subscriptionId)
    }
}
