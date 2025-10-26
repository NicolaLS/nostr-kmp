@file:OptIn(ExperimentalUnsignedTypes::class)

import nostr.core.model.Event
import nostr.core.model.UnsignedEvent
import nostr.core.utils.hexToByteArray

/**
 * Determines which encryption scheme should be used when wrapping event content.
 *
 * - [Auto] uses heuristics: legacy kind 4 messages fall back to NIP-04, everything else prefers NIP-44.
 * - [Nip04] forces the legacy scheme (callers must supply a [legacyEncryptor]/[legacyDecryptor]).
 * - [Nip44] forces the modern payload format implemented in this module.
 */
enum class EncryptionPreference {
    Auto,
    Nip04,
    Nip44,
}

private enum class EncryptionScheme { Nip04, Nip44 }

typealias Nip04Encryptor = (plaintext: String, senderPrivateKeyHex: String, recipientPubkeyHex: String) -> String
typealias Nip04Decryptor = (ciphertext: String, recipientPrivateKeyHex: String, senderPubkeyHex: String) -> String

/**
 * Encrypts the [UnsignedEvent.content] using NIP-44 (or legacy NIP-04 when requested) and
 * returns a new [UnsignedEvent] with the encrypted payload.
 *
 * When [preference] is [EncryptionPreference.Auto] and the event [kind][UnsignedEvent.kind] is `4`,
 * the function assumes a legacy DM and delegates to the supplied [legacyEncryptor]. For all other
 * kinds NIP-44 is applied automatically. If the [recipientPubkeyHex] is omitted, the function
 * attempts to discover the counterparty from the first `p` tag.
 */
fun UnsignedEvent.encryptWith(
    senderPrivateKeyHex: String,
    recipientPubkeyHex: String? = null,
    preference: EncryptionPreference = EncryptionPreference.Auto,
    legacyEncryptor: Nip04Encryptor? = null,
): UnsignedEvent {
    val resolvedRecipient = recipientPubkeyHex ?: firstRecipientTag()
        ?: throw IllegalArgumentException("Recipient pubkey is required (either pass it explicitly or add a 'p' tag)")
    val scheme = preference.resolveForEncryption(kind)
    return when (scheme) {
        EncryptionScheme.Nip44 -> {
            val conversationKey = calculateConversationKey(
                senderPrivateKeyHex.requireHexKey(32, "sender private key"),
                resolvedRecipient.requireHexKey(32, "recipient public key")
            )
            val ciphertext = encrypt(content, conversationKey)
            copy(content = ciphertext)
        }

        EncryptionScheme.Nip04 -> {
            val encryptor = legacyEncryptor
                ?: throw UnsupportedOperationException("NIP-04 encryption requested but no legacy encryptor provided")
            copy(content = encryptor(content, senderPrivateKeyHex, resolvedRecipient))
        }
    }
}

/**
 * Decrypts the encrypted [Event.content] using NIP-44 (or NIP-04 when heuristics detect the legacy format)
 * and returns the plaintext string. The event itself remains untouched so the original signature stays valid.
 *
 * When [preference] is [EncryptionPreference.Auto], the function falls back to NIP-04 when either the event
 * kind is `4` or the ciphertext contains the legacy `?iv=` marker. In that case the optional [legacyDecryptor]
 * must be provided.
 *
 * @param recipientPrivateKeyHex x-only private key of the receiver (64 lowercase hex characters).
 * @param preference preferred scheme selection strategy. Defaults to [EncryptionPreference.Auto].
 * @param legacyDecryptor optional callback that handles legacy NIP-04 payloads.
 * @param senderPubkeyOverride override for the sender pubkey when it differs from [Event.pubkey] (rare).
 */
fun Event.decryptWith(
    recipientPrivateKeyHex: String,
    preference: EncryptionPreference = EncryptionPreference.Auto,
    legacyDecryptor: Nip04Decryptor? = null,
    senderPubkeyOverride: String? = null,
): String {
    val scheme = preference.resolveForDecryption(kind, content)
    return when (scheme) {
        EncryptionScheme.Nip44 -> {
            val conversationKey = calculateConversationKey(
                recipientPrivateKeyHex.requireHexKey(32, "recipient private key"),
                (senderPubkeyOverride ?: pubkey).requireHexKey(32, "sender public key")
            )
            decrypt(content, conversationKey)
        }

        EncryptionScheme.Nip04 -> {
            val decryptor = legacyDecryptor
                ?: throw UnsupportedOperationException("NIP-04 ciphertext detected but no legacy decryptor provided")
            decryptor(content, recipientPrivateKeyHex, senderPubkeyOverride ?: pubkey)
        }
    }
}

private fun EncryptionPreference.resolveForEncryption(kind: Int): EncryptionScheme = when (this) {
    EncryptionPreference.Nip04 -> EncryptionScheme.Nip04
    EncryptionPreference.Nip44 -> EncryptionScheme.Nip44
    EncryptionPreference.Auto -> if (kind == 4) EncryptionScheme.Nip04 else EncryptionScheme.Nip44
}

private fun EncryptionPreference.resolveForDecryption(kind: Int, content: String): EncryptionScheme = when (this) {
    EncryptionPreference.Nip04 -> EncryptionScheme.Nip04
    EncryptionPreference.Nip44 -> EncryptionScheme.Nip44
    EncryptionPreference.Auto -> if (kind == 4 || content.contains("?iv=")) EncryptionScheme.Nip04 else EncryptionScheme.Nip44
}

private fun UnsignedEvent.firstRecipientTag(): String? =
    tags.firstOrNull { it.getOrNull(0) == "p" }?.getOrNull(1)

private fun String.requireHexKey(expectedBytes: Int, label: String): ByteArray {
    val bytes = this.hexToByteArray()
    require(bytes.size == expectedBytes) { "$label must decode to $expectedBytes bytes but was ${bytes.size}" }
    return bytes
}
