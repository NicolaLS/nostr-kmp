package io.github.nicolals.nostr.nip04

import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SecretKey

/**
 * NIP-04 encryption for an account whose private key need not be available to the caller.
 * Implementations may suspend for remote signing services or user approval; cancellation propagates.
 * Validate the outer event before decrypting received content, or use [decryptEvent].
 */
interface Nip04Cipher {
    suspend fun encrypt(plaintext: String, recipientPublicKey: PublicKey): CryptoResult<String>
    suspend fun decrypt(content: String, senderPublicKey: PublicKey): CryptoResult<String>
}

/** Local implementation; private key material is retained without being exposed by the capability. */
class LocalNip04Cipher(
    private val secretKey: SecretKey,
    private val crypto: CryptoPrimitives,
) : Nip04Cipher {
    override suspend fun encrypt(plaintext: String, recipientPublicKey: PublicKey): CryptoResult<String> =
        crypto.encryptNip04Content(plaintext, secretKey.bytes, recipientPublicKey)

    override suspend fun decrypt(content: String, senderPublicKey: PublicKey): CryptoResult<String> =
        crypto.decryptNip04Content(content, secretKey.bytes, senderPublicKey)

    override fun toString(): String = "LocalNip04Cipher([redacted])"
}

/** Authenticates an incoming event before passing its exact content and author to the cipher. */
suspend fun Nip04Cipher.decryptEvent(event: Event, verifier: EventVerifier): CryptoResult<String> =
    verifier.verify(event).flatMap { decrypt(event.content, event.pubkey) }
