package io.github.nicolals.nostr.nip45.message

import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader

/**
 * Upgrader for NIP-45 client messages: COUNT.
 */
class Nip45ClientMessageUpgrader(
    private val wireCodec: NostrWireCodec,
) : ClientMessageUpgrader<CountClientMessage> {

    override val tokens: Set<String> = setOf(CountClientMessage.TOKEN)

    override fun upgradeOrNull(raw: RawClientMessage, token: String): CountClientMessage? {
        return when (token) {
            CountClientMessage.TOKEN -> upgradeCountMessage(raw)
            else -> null
        }
    }

    private fun upgradeCountMessage(raw: RawClientMessage): CountClientMessage? {
        // ["COUNT", <subscription_id>, <filter>...]
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

        return CountClientMessage(
            raw = raw,
            subscriptionIdString = subscriptionIdString,
            subscriptionId = subscriptionId,
            wireFilters = wireFilters,
            filters = if (allFiltersValid) filters.filterNotNull() else null,
        )
    }
}
