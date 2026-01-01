package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTag
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.decodeNwcResponseOrNull
import io.github.nicolals.nostr.nip47.decryptNwcContent
import io.github.nicolals.nostr.nip47.encodeNwcResponse
import io.github.nicolals.nostr.nip47.encryptNwcContent
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcResponse
import okio.ByteString

open class NwcResponseEvent(
    override val event: Event,
    val clientPubkey: PublicKey,
    val requestEventId: EventId?,
    val responseId: String?,
    val encryptionTag: String?,
    val encryption: NwcEncryption,
    private val knownResponse: NwcResponse? = null,
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

        fun template(
            clientPubkey: PublicKey,
            response: NwcResponse,
            createdAt: UnixTimeSeconds,
            ctx: NipModuleContext,
            walletPrivateKey: ByteString,
            encryption: NwcEncryption = NwcEncryption.NIP44_V2,
            requestEventId: EventId? = null,
            responseId: String? = null,
            extraTags: EventTags = emptyList(),
        ): CryptoResult<EventTemplate<NwcResponseEvent>> {
            val payload = ctx.encodeNwcResponse(response)
            return ctx.encryptNwcContent(payload, encryption, walletPrivateKey, clientPubkey).map { content ->
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
                        knownResponse = response,
                    )
                }
            }
        }
    }

    fun decryptContent(
        ctx: NipModuleContext,
        receiverPrivateKey: ByteString,
        senderPublicKey: PublicKey,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<String> {
        val resolved = resolveEncryption(encryptionOverride)
        return ctx.decryptNwcContent(event.content, resolved, receiverPrivateKey, senderPublicKey)
    }

    fun decryptResponse(
        ctx: NipModuleContext,
        receiverPrivateKey: ByteString,
        senderPublicKey: PublicKey,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcResponse> {
        knownResponse?.let { return CryptoResult.Ok(it) }
        return decryptContent(ctx, receiverPrivateKey, senderPublicKey, encryptionOverride).flatMap { plaintext ->
            val response = ctx.decodeNwcResponseOrNull(plaintext)
                ?: return@flatMap CryptoResult.Err(CryptoError.DecodeError("nwc response"))
            CryptoResult.Ok(response)
        }
    }

    fun decryptForClient(
        ctx: NipModuleContext,
        clientPrivateKey: ByteString,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcResponse> =
        decryptResponse(ctx, clientPrivateKey, event.pubkey, encryptionOverride)

    fun decryptForWallet(
        ctx: NipModuleContext,
        walletPrivateKey: ByteString,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcResponse> =
        decryptResponse(ctx, walletPrivateKey, clientPubkey, encryptionOverride)

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
