package io.github.nicolals.nostr.nip45.message

import io.github.nicolals.nostr.core.codec.JsonObject
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.booleanValue
import io.github.nicolals.nostr.core.codec.longValueOrNull
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader

/**
 * Upgrader for NIP-45 relay messages: COUNT.
 */
class Nip45RelayMessageUpgrader : RelayMessageUpgrader<CountRelayMessage> {

    override val tokens: Set<String> = setOf(CountRelayMessage.TOKEN)

    override fun upgradeOrNull(raw: RawRelayMessage, token: String): CountRelayMessage? {
        return when (token) {
            CountRelayMessage.TOKEN -> upgradeCountMessage(raw)
            else -> null
        }
    }

    private fun upgradeCountMessage(raw: RawRelayMessage): CountRelayMessage? {
        // ["COUNT", <subscription_id>, {"count": <integer>, "approximate": <true|false>?}]
        if (raw.payloadSize != 2) return null

        val subscriptionIdString = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null
        val subscriptionId = subscriptionIdString.toSubscriptionIdOrNull()

        val countObj = raw.payloadAtOrNull(1) as? JsonObject ?: return null
        val count = countObj.longValueOrNull("count") ?: return null
        val approximate = countObj.booleanValue("approximate")

        return CountRelayMessage(
            raw = raw,
            subscriptionIdString = subscriptionIdString,
            subscriptionId = subscriptionId,
            result = CountResult(count = count, approximate = approximate),
        )
    }
}
