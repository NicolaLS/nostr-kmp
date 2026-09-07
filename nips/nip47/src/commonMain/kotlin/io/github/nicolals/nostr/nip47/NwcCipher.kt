package io.github.nicolals.nostr.nip47

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.nip04.Nip04Cipher
import io.github.nicolals.nostr.nip44.Nip44Cipher
import io.github.nicolals.nostr.nip47.model.NwcEncryption

/** Encryption capability for one NWC identity, including remote or hardware-backed keys. */
interface NwcCipher {
    suspend fun encrypt(plaintext: String, encryption: NwcEncryption, recipientPublicKey: PublicKey): CryptoResult<String>
    suspend fun decrypt(content: String, encryption: NwcEncryption, senderPublicKey: PublicKey): CryptoResult<String>
}

/** Combines the separately injectable NIP-04 and NIP-44 capabilities. */
class Nip47Cipher(
    private val nip04: Nip04Cipher,
    private val nip44: Nip44Cipher,
) : NwcCipher {
    override suspend fun encrypt(plaintext: String, encryption: NwcEncryption, recipientPublicKey: PublicKey): CryptoResult<String> =
        when (encryption) {
            NwcEncryption.NIP04 -> nip04.encrypt(plaintext, recipientPublicKey)
            NwcEncryption.NIP44_V2 -> nip44.encrypt(plaintext, recipientPublicKey)
        }

    override suspend fun decrypt(content: String, encryption: NwcEncryption, senderPublicKey: PublicKey): CryptoResult<String> =
        when (encryption) {
            NwcEncryption.NIP04 -> nip04.decrypt(content, senderPublicKey)
            NwcEncryption.NIP44_V2 -> nip44.decrypt(content, senderPublicKey)
        }
}
