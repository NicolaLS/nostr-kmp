package io.github.nicolals.nostr.nip42.message

import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.primitives.*

/**
 * Upgrader for NIP-42 relay AUTH messages (challenge).
 */
class Nip42RelayMessageUpgrader : RelayMessageUpgrader<AuthChallengeMessage> {

    override val tokens: Set<String> = setOf(AUTH_TOKEN)

    override fun upgradeOrNull(raw: RawRelayMessage, token: String): AuthChallengeMessage? {
        // ["AUTH", <challenge-string>]
        if (raw.payloadSize != 1) return null

        val challenge = (raw.payloadAtOrNull(0) as? JsonString)?.value ?: return null

        return AuthChallengeMessage(raw, challenge)
    }
}

/**
 * Upgrader for NIP-42 client AUTH messages (signed event).
 */
class Nip42ClientMessageUpgrader(
    private val wireCodec: NostrWireCodec,
) : ClientMessageUpgrader<AuthClientMessage> {

    override val tokens: Set<String> = setOf(AUTH_TOKEN)

    override fun upgradeOrNull(raw: RawClientMessage, token: String): AuthClientMessage? {
        // ["AUTH", <signed-event-json>]
        if (raw.payloadSize != 1) return null

        val wireEvent = runCatching { wireCodec.decodeEvent(raw.payloadAt(0)) }.getOrNull()
            ?: return null

        val event = wireEvent.toEventOrNull()

        return AuthClientMessage(raw, wireEvent, event)
    }
}

/**
 * Convert a wire Event to domain format.
 * Returns null if any field fails validation.
 */
private fun io.github.nicolals.nostr.core.codec.wire.WireEvent.toEventOrNull(): Event? = runCatching {
    Event(
        id = EventId.fromHex(id),
        pubkey = PublicKey.fromHex(pubkey),
        createdAt = UnixTimeSeconds(createdAt),
        kind = EventKind(kind),
        tags = tags,
        content = content,
        sig = EventSig.fromHex(sig),
    )
}.getOrNull()
