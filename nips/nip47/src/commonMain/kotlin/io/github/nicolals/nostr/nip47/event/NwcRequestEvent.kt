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
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.decodeNwcRequestOrNull
import io.github.nicolals.nostr.nip47.encodeNwcRequest
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcRequest

/**
 * A parsed event view, not proof of its sender's identity. Before decrypting received events,
 * verify the canonical ID/signature and check the expected sender and recipient.
 */
open class NwcRequestEvent(
    override val event: Event,
    val walletPubkey: PublicKey,
    val expiration: UnixTimeSeconds?,
    val encryptionTag: String?,
    val encryption: NwcEncryption,
) : EventView {
    companion object {
        val KIND = EventKind(23194)

        fun fromEventOrNull(
            event: Event,
            encryptionSelector: NwcEncryptionSelector,
        ): NwcRequestEvent? {
            if (event.kind != KIND) return null
            val walletPubkey = pubkeyFromTags(event.tags, "p") ?: return null
            val encryptionTag = encryptionTagFromTags(event.tags)
            val encryptionFromTag = encryptionTag?.let(NwcEncryption::fromTag)
            val encryption = encryptionFromTag ?: encryptionSelector.select(walletPubkey)
            val expiration = expirationFromTags(event.tags)
            return NwcRequestEvent(
                event = event,
                walletPubkey = walletPubkey,
                expiration = expiration,
                encryptionTag = encryptionTag,
                encryption = encryption,
            )
        }

        suspend fun template(
            walletPubkey: PublicKey,
            request: NwcRequest,
            createdAt: UnixTimeSeconds,
            codec: JsonCodec,
            cipher: NwcCipher,
            encryptionSelector: NwcEncryptionSelector,
            expiration: UnixTimeSeconds? = null,
            extraTags: EventTags = emptyList(),
        ): CryptoResult<EventTemplate<NwcRequestEvent>> {
            val encryption = encryptionSelector.select(walletPubkey)
            return template(
                walletPubkey = walletPubkey,
                request = request,
                createdAt = createdAt,
                codec = codec,
                cipher = cipher,
                encryption = encryption,
                expiration = expiration,
                extraTags = extraTags,
            )
        }

        /**
         * Defaults to NIP-44; override when targeting legacy wallets that only support NIP-04.
         */
        suspend fun template(
            walletPubkey: PublicKey,
            request: NwcRequest,
            createdAt: UnixTimeSeconds,
            codec: JsonCodec,
            cipher: NwcCipher,
            encryption: NwcEncryption = NwcEncryption.NIP44_V2,
            expiration: UnixTimeSeconds? = null,
            extraTags: EventTags = emptyList(),
        ): CryptoResult<EventTemplate<NwcRequestEvent>> {
            val payload = codec.encodeNwcRequest(request)
            return cipher.encrypt(payload, encryption, walletPubkey).map { content ->
                val tags = buildTags(walletPubkey, encryption, expiration, extraTags)
                val draft = EventDraft(
                    createdAt = createdAt,
                    kind = KIND,
                    tags = tags,
                    content = content,
                )
                EventTemplate(draft) { event ->
                    NwcRequestEvent(
                        event = event,
                        walletPubkey = walletPubkey,
                        expiration = expiration,
                        encryptionTag = encryption.tag,
                        encryption = encryption,
                    )
                }
            }
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

    suspend fun decryptRequest(
        codec: JsonCodec,
        cipher: NwcCipher,
        senderPublicKey: PublicKey,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcRequest> {
        return decryptContent(cipher, senderPublicKey, encryptionOverride).flatMap { plaintext ->
            val request = codec.decodeNwcRequestOrNull(plaintext)
                ?: return@flatMap CryptoResult.Err(CryptoError.DecodeError("nwc request"))
            CryptoResult.Ok(request)
        }
    }

    suspend fun decryptForWallet(
        codec: JsonCodec,
        cipher: NwcCipher,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcRequest> =
        decryptRequest(codec, cipher, event.pubkey, encryptionOverride)

    suspend fun decryptForClient(
        codec: JsonCodec,
        cipher: NwcCipher,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcRequest> =
        decryptRequest(codec, cipher, walletPubkey, encryptionOverride)

    private fun resolveEncryption(encryptionOverride: NwcEncryption?): NwcEncryption =
        encryptionOverride ?: encryption
}

private fun buildTags(
    walletPubkey: PublicKey,
    encryption: NwcEncryption,
    expiration: UnixTimeSeconds?,
    extraTags: EventTags,
): EventTags {
    val tags = mutableListOf<EventTag>()
    tags += listOf("p", walletPubkey.hex)
    tags += listOf("encryption", encryption.tag)
    expiration?.let { tags += listOf("expiration", it.value.toString()) }
    tags.addAll(extraTags)
    return tags
}

private fun encryptionTagFromTags(tags: EventTags): String? =
    tagValue(tags, "encryption")?.trim()?.split(' ')?.firstOrNull { it.isNotBlank() }

private fun expirationFromTags(tags: EventTags): UnixTimeSeconds? =
    tagValue(tags, "expiration")?.toLongOrNull()?.let(::UnixTimeSeconds)

private fun tagValue(tags: EventTags, name: String): String? =
    tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

private fun pubkeyFromTags(tags: EventTags, name: String): PublicKey? {
    val value = tagValue(tags, name) ?: return null
    return runCatching { PublicKey.fromHex(value) }.getOrNull()
}
