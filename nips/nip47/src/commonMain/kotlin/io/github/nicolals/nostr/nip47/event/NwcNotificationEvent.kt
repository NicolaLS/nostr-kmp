package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.nip47.NwcCipher
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTag
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip47.decodeNwcNotificationOrNull
import io.github.nicolals.nostr.nip47.encodeNwcNotification
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcNotification

/**
 * A parsed event view, not proof of its sender's identity. Before decrypting received events,
 * verify the canonical ID/signature and check the expected sender and recipient.
 */
open class NwcNotificationEvent(
    override val event: Event,
    val clientPubkey: PublicKey,
    val encryption: NwcEncryption,
) : EventView {
    companion object {
        val KIND = EventKind(23197)
        val LEGACY_KIND = EventKind(23196)

        fun fromEventOrNull(event: Event): NwcNotificationEvent? {
            val encryption = when (event.kind) {
                KIND -> NwcEncryption.NIP44_V2
                LEGACY_KIND -> NwcEncryption.NIP04
                else -> return null
            }
            val clientPubkey = pubkeyFromTags(event.tags, "p") ?: return null
            return NwcNotificationEvent(
                event = event,
                clientPubkey = clientPubkey,
                encryption = encryption,
            )
        }

        suspend fun template(
            clientPubkey: PublicKey,
            notification: NwcNotification,
            createdAt: UnixTimeSeconds,
            codec: JsonCodec,
            cipher: NwcCipher,
            encryption: NwcEncryption = NwcEncryption.NIP44_V2,
            extraTags: EventTags = emptyList(),
        ): CryptoResult<EventTemplate<NwcNotificationEvent>> {
            val payload = codec.encodeNwcNotification(notification)
            return cipher.encrypt(payload, encryption, clientPubkey).map { content ->
                val kind = kindForEncryption(encryption)
                val tags = buildTags(clientPubkey, extraTags)
                val draft = EventDraft(
                    createdAt = createdAt,
                    kind = kind,
                    tags = tags,
                    content = content,
                )
                EventTemplate(draft) { event ->
                    NwcNotificationEvent(
                        event = event,
                        clientPubkey = clientPubkey,
                        encryption = encryption,
                    )
                }
            }
        }

        fun kindForEncryption(encryption: NwcEncryption): EventKind = when (encryption) {
            NwcEncryption.NIP44_V2 -> KIND
            NwcEncryption.NIP04 -> LEGACY_KIND
        }
    }

    suspend fun decryptContent(
        cipher: NwcCipher,
        senderPublicKey: PublicKey,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<String> {
        val resolved = resolveEncryption(encryptionOverride)
        return cipher.decrypt(event.content, resolved, senderPublicKey)
    }

    suspend fun decryptNotification(
        codec: JsonCodec,
        cipher: NwcCipher,
        senderPublicKey: PublicKey,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcNotification> {
        return decryptContent(cipher, senderPublicKey, encryptionOverride).flatMap { plaintext ->
            val notification = codec.decodeNwcNotificationOrNull(plaintext)
                ?: return@flatMap CryptoResult.Err(CryptoError.DecodeError("nwc notification"))
            CryptoResult.Ok(notification)
        }
    }

    suspend fun decryptForClient(
        codec: JsonCodec,
        cipher: NwcCipher,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcNotification> =
        decryptNotification(codec, cipher, event.pubkey, encryptionOverride)

    suspend fun decryptForWallet(
        codec: JsonCodec,
        cipher: NwcCipher,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcNotification> =
        decryptNotification(codec, cipher, clientPubkey, encryptionOverride)

    private fun resolveEncryption(encryptionOverride: NwcEncryption?): NwcEncryption =
        encryptionOverride ?: encryption
}

private fun buildTags(
    clientPubkey: PublicKey,
    extraTags: EventTags,
): EventTags {
    val tags = mutableListOf<EventTag>()
    tags += listOf("p", clientPubkey.hex)
    tags.addAll(extraTags)
    return tags
}

private fun pubkeyFromTags(tags: EventTags, name: String): PublicKey? {
    val tag = tags.firstOrNull { it.size >= 2 && it[0] == name } ?: return null
    return runCatching { PublicKey.fromHex(tag[1]) }.getOrNull()
}
