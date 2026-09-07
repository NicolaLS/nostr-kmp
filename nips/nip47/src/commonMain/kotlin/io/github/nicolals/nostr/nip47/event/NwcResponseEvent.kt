package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.nip47.NwcCipher
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTag
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.decodeNwcResponseOrNull
import io.github.nicolals.nostr.nip47.encodeNwcResponse
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcResponse

/**
 * A parsed event view, not proof of its sender's identity. Before decrypting received events,
 * verify the canonical ID/signature and check the expected sender and recipient.
 */
open class NwcResponseEvent(
    override val event: Event,
    val clientPubkey: PublicKey,
    val requestEventId: EventId?,
    val responseId: String?,
    val encryptionTag: String?,
    val encryption: NwcEncryption,
) : EventView {
    companion object {
        val KIND = EventKind(23195)

        fun fromEventOrNull(
            event: Event,
            encryptionSelector: NwcEncryptionSelector,
        ): NwcResponseEvent? {
            if (event.kind != KIND) return null
            val clientPubkey = pubkeyFromTags(event.tags, "p") ?: return null
            val requestEventId = eventIdFromTags(event.tags, "e")
            val responseId = tagValue(event.tags, "d")
            val encryptionTag = encryptionTagFromTags(event.tags)
            val encryptionFromTag = encryptionTag?.let(NwcEncryption::fromTag)
            val encryption = encryptionFromTag ?: encryptionSelector.select(event.pubkey)
            return NwcResponseEvent(
                event = event,
                clientPubkey = clientPubkey,
                requestEventId = requestEventId,
                responseId = responseId,
                encryptionTag = encryptionTag,
                encryption = encryption,
            )
        }

        suspend fun template(
            clientPubkey: PublicKey,
            response: NwcResponse,
            createdAt: UnixTimeSeconds,
            codec: JsonCodec,
            cipher: NwcCipher,
            encryption: NwcEncryption = NwcEncryption.NIP44_V2,
            requestEventId: EventId? = null,
            responseId: String? = null,
            extraTags: EventTags = emptyList(),
        ): CryptoResult<EventTemplate<NwcResponseEvent>> {
            val payload = codec.encodeNwcResponse(response)
            return cipher.encrypt(payload, encryption, clientPubkey).map { content ->
                val tags = buildTags(clientPubkey, requestEventId, responseId, encryption, extraTags)
                val draft = EventDraft(
                    createdAt = createdAt,
                    kind = KIND,
                    tags = tags,
                    content = content,
                )
                EventTemplate(draft) { event ->
                    NwcResponseEvent(
                        event = event,
                        clientPubkey = clientPubkey,
                        requestEventId = requestEventId,
                        responseId = responseId,
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

    suspend fun decryptResponse(
        codec: JsonCodec,
        cipher: NwcCipher,
        senderPublicKey: PublicKey,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcResponse> {
        return decryptContent(cipher, senderPublicKey, encryptionOverride).flatMap { plaintext ->
            val response = codec.decodeNwcResponseOrNull(plaintext)
                ?: return@flatMap CryptoResult.Err(CryptoError.DecodeError("nwc response"))
            CryptoResult.Ok(response)
        }
    }

    suspend fun decryptForClient(
        codec: JsonCodec,
        cipher: NwcCipher,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcResponse> =
        decryptResponse(codec, cipher, event.pubkey, encryptionOverride)

    suspend fun decryptForWallet(
        codec: JsonCodec,
        cipher: NwcCipher,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcResponse> =
        decryptResponse(codec, cipher, clientPubkey, encryptionOverride)

    private fun resolveEncryption(encryptionOverride: NwcEncryption?): NwcEncryption =
        encryptionOverride ?: encryption
}

private fun buildTags(
    clientPubkey: PublicKey,
    requestEventId: EventId?,
    responseId: String?,
    encryption: NwcEncryption,
    extraTags: EventTags,
): EventTags {
    val tags = mutableListOf<EventTag>()
    tags += listOf("p", clientPubkey.hex)
    requestEventId?.let { tags += listOf("e", it.hex) }
    responseId?.let { tags += listOf("d", it) }
    tags += listOf("encryption", encryption.tag)
    tags.addAll(extraTags)
    return tags
}

private fun encryptionTagFromTags(tags: EventTags): String? =
    tagValue(tags, "encryption")?.trim()?.split(' ')?.firstOrNull { it.isNotBlank() }

private fun tagValue(tags: EventTags, name: String): String? =
    tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

private fun pubkeyFromTags(tags: EventTags, name: String): PublicKey? {
    val value = tagValue(tags, name) ?: return null
    return runCatching { PublicKey.fromHex(value) }.getOrNull()
}

private fun eventIdFromTags(tags: EventTags, name: String): EventId? {
    val value = tagValue(tags, name) ?: return null
    return runCatching { EventId.fromHex(value) }.getOrNull()
}
