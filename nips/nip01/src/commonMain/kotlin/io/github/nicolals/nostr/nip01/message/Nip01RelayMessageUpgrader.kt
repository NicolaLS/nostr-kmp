package io.github.nicolals.nostr.nip01.message

import io.github.nicolals.nostr.core.codec.JsonBoolean
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.message.view.RelayMessageView

/**
 * Upgrader for NIP-01 relay messages: EVENT, OK, EOSE, CLOSED, NOTICE.
 */
class Nip01RelayMessageUpgrader(
    private val wireCodec: NostrWireCodec,
) : RelayMessageUpgrader<RelayMessageView> {

    override val tokens: Set<String> = setOf(
        RelayEventMessage.TOKEN,
        OkMessage.TOKEN,
        EoseMessage.TOKEN,
        ClosedMessage.TOKEN,
        NoticeMessage.TOKEN,
    )

    override fun upgradeOrNull(raw: RawRelayMessage, token: String): RelayMessageView? {
        return when (token) {
            RelayEventMessage.TOKEN -> upgradeEventMessage(raw)
            OkMessage.TOKEN -> upgradeOkMessage(raw)
            EoseMessage.TOKEN -> upgradeEoseMessage(raw)
            ClosedMessage.TOKEN -> upgradeClosedMessage(raw)
            NoticeMessage.TOKEN -> upgradeNoticeMessage(raw)
            else -> null
        }
    }

    private fun upgradeEventMessage(raw: RawRelayMessage): RelayEventMessage? {
        // ["EVENT", <subscription_id>, <event>]
        if (raw.payloadSize != 2) return null

        val subscriptionIdString = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null
        val subscriptionId = subscriptionIdString.toSubscriptionIdOrNull()

        val wireEvent = runCatching { wireCodec.decodeEvent(raw.payloadAt(1)) }.getOrNull()
            ?: return null
        val event = wireEvent.toEventOrNull()

        return RelayEventMessage(
            raw = raw,
            subscriptionIdString = subscriptionIdString,
            subscriptionId = subscriptionId,
            wireEvent = wireEvent,
            event = event,
        )
    }

    private fun upgradeOkMessage(raw: RawRelayMessage): OkMessage? {
        // ["OK", <event_id>, <true|false>, <message>]
        if (raw.payloadSize != 3) return null

        val eventIdHex = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null
        val eventId = eventIdHex.toEventIdOrNull()

        val accepted = (raw.payloadAtOrNull(1) as? JsonBoolean)?.value ?: return null

        val message = (raw.payloadAtOrNull(2) as? JsonString)?.value ?: return null

        return OkMessage(
            raw = raw,
            eventIdHex = eventIdHex,
            eventId = eventId,
            accepted = accepted,
            message = message,
        )
    }

    private fun upgradeEoseMessage(raw: RawRelayMessage): EoseMessage? {
        // ["EOSE", <subscription_id>]
        if (raw.payloadSize != 1) return null

        val subscriptionIdString = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null
        val subscriptionId = subscriptionIdString.toSubscriptionIdOrNull()

        return EoseMessage(raw, subscriptionIdString, subscriptionId)
    }

    private fun upgradeClosedMessage(raw: RawRelayMessage): ClosedMessage? {
        // ["CLOSED", <subscription_id>, <message>]
        if (raw.payloadSize != 2) return null

        val subscriptionIdString = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null
        val subscriptionId = subscriptionIdString.toSubscriptionIdOrNull()

        val message = (raw.payloadAtOrNull(1) as? JsonString)?.value ?: return null

        return ClosedMessage(raw, subscriptionIdString, subscriptionId, message)
    }

    private fun upgradeNoticeMessage(raw: RawRelayMessage): NoticeMessage? {
        // ["NOTICE", <message>]
        if (raw.payloadSize != 1) return null

        val message = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null

        return NoticeMessage(raw, message)
    }
}
