package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTag
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip47.decodeNwcNotificationOrNull
import io.github.nicolals.nostr.nip47.decryptNwcContent
import io.github.nicolals.nostr.nip47.encodeNwcNotification
import io.github.nicolals.nostr.nip47.encryptNwcContent
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcNotification
import okio.ByteString

open class NwcNotificationEvent(
    override val event: Event,
    val clientPubkey: PublicKey,
    val encryption: NwcEncryption,
    private val knownNotification: NwcNotification? = null,
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

        fun template(
            clientPubkey: PublicKey,
            notification: NwcNotification,
            createdAt: UnixTimeSeconds,
            ctx: NipModuleContext,
            walletPrivateKey: ByteString,
            encryption: NwcEncryption = NwcEncryption.NIP44_V2,
            extraTags: EventTags = emptyList(),
        ): CryptoResult<EventTemplate<NwcNotificationEvent>> {
            val payload = ctx.encodeNwcNotification(notification)
            return ctx.encryptNwcContent(payload, encryption, walletPrivateKey, clientPubkey).map { content ->
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
                        knownNotification = notification,
                    )
                }
            }
        }

        fun kindForEncryption(encryption: NwcEncryption): EventKind = when (encryption) {
            NwcEncryption.NIP44_V2 -> KIND
            NwcEncryption.NIP04 -> LEGACY_KIND
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

    fun decryptNotification(
        ctx: NipModuleContext,
        receiverPrivateKey: ByteString,
        senderPublicKey: PublicKey,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcNotification> {
        knownNotification?.let { return CryptoResult.Ok(it) }
        return decryptContent(ctx, receiverPrivateKey, senderPublicKey, encryptionOverride).flatMap { plaintext ->
            val notification = ctx.decodeNwcNotificationOrNull(plaintext)
                ?: return@flatMap CryptoResult.Err(CryptoError.DecodeError("nwc notification"))
            CryptoResult.Ok(notification)
        }
    }

    fun decryptForClient(
        ctx: NipModuleContext,
        clientPrivateKey: ByteString,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcNotification> =
        decryptNotification(ctx, clientPrivateKey, event.pubkey, encryptionOverride)

    fun decryptForWallet(
        ctx: NipModuleContext,
        walletPrivateKey: ByteString,
        encryptionOverride: NwcEncryption? = null,
    ): CryptoResult<NwcNotification> =
        decryptNotification(ctx, walletPrivateKey, clientPubkey, encryptionOverride)

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
