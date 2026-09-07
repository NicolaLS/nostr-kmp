package io.github.nicolals.nostr.nip04.event

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTag
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip04.Nip04Cipher
import io.github.nicolals.nostr.nip04.model.Nip04CipherText

open class EncryptedDirectMessageEvent(
    override val event: Event,
    open val recipientPubkey: PublicKey,
    open val replyToEventId: EventId?,
    open val ciphertext: Nip04CipherText,
) : EventView {
    companion object {
        val KIND = EventKind(4)

        fun fromEventOrNull(event: Event): EncryptedDirectMessageEvent? {
            if (event.kind != KIND) {
                return null
            }
            val ciphertext = Nip04CipherText.parseOrNull(event.content) ?: return null
            val recipientPubkey = recipientPubkeyFromTags(event.tags) ?: return null
            val replyToEventId = replyToEventIdFromTags(event.tags)
            return EncryptedDirectMessageEvent(
                event = event,
                recipientPubkey = recipientPubkey,
                replyToEventId = replyToEventId,
                ciphertext = ciphertext,
            )
        }

        suspend fun template(
            recipientPubkey: PublicKey,
            plaintext: String,
            createdAt: UnixTimeSeconds,
            cipher: Nip04Cipher,
            replyToEventId: EventId? = null,
            extraTags: EventTags = emptyList(),
        ): CryptoResult<EventTemplate<EncryptedDirectMessageEvent>> =
            cipher.encrypt(plaintext, recipientPubkey).flatMap { content ->
                val ciphertext = Nip04CipherText.parseOrNull(content)
                    ?: return@flatMap CryptoResult.Err(
                        CryptoError.DecodeError("nip04"),
                    )
                CryptoResult.Ok(
                    template(
                        recipientPubkey = recipientPubkey,
                        ciphertext = ciphertext,
                        createdAt = createdAt,
                        replyToEventId = replyToEventId,
                        extraTags = extraTags,
                    ),
                )
            }

        fun template(
            recipientPubkey: PublicKey,
            ciphertext: Nip04CipherText,
            createdAt: UnixTimeSeconds,
            replyToEventId: EventId? = null,
            extraTags: EventTags = emptyList(),
        ): EventTemplate<EncryptedDirectMessageEvent> {
            val tags = buildTags(recipientPubkey, replyToEventId, extraTags)
            val draft = EventDraft(
                createdAt = createdAt,
                kind = KIND,
                tags = tags,
                content = ciphertext.toContent(),
            )
            return EventTemplate(draft) { event ->
                EncryptedDirectMessageEvent(
                    event = event,
                    recipientPubkey = recipientPubkey,
                    replyToEventId = replyToEventId,
                    ciphertext = ciphertext,
                )
            }
        }
    }

    suspend fun decryptForRecipient(
        cipher: Nip04Cipher,
        verifier: EventVerifier,
    ): CryptoResult<String> = verifier.verify(event).flatMap {
        cipher.decrypt(event.content, event.pubkey)
    }

    suspend fun decryptForSender(
        cipher: Nip04Cipher,
        verifier: EventVerifier,
    ): CryptoResult<String> = verifier.verify(event).flatMap {
        val recipient = recipientPubkeyFromTags(event.tags)
            ?: return@flatMap CryptoResult.Err(
                CryptoError.DecryptionFailed("Missing recipient"),
            )
        cipher.decrypt(event.content, recipient)
    }
}

private fun recipientPubkeyFromTags(tags: EventTags): PublicKey? {
    val recipientTag = tags.firstOrNull { it.size >= 2 && it[0] == "p" } ?: return null
    return runCatching { PublicKey.fromHex(recipientTag[1]) }.getOrNull()
}

private fun replyToEventIdFromTags(tags: EventTags): EventId? {
    val replyTag = tags.firstOrNull { it.size >= 2 && it[0] == "e" } ?: return null
    return runCatching { EventId.fromHex(replyTag[1]) }.getOrNull()
}

private fun buildTags(
    recipientPubkey: PublicKey,
    replyToEventId: EventId?,
    extraTags: EventTags,
): EventTags {
    val tags = mutableListOf<EventTag>()
    tags.add(listOf("p", recipientPubkey.hex))
    replyToEventId?.let { tags.add(listOf("e", it.hex)) }
    tags.addAll(extraTags)
    return tags
}
