package io.github.nicolals.nostr.core.crypto

import okio.ByteString

/**
 * Cryptographic primitives for key generation, derivation, and signing.
 *
 * This interface is separate from [CryptoPrimitives] (which handles encryption/hashing)
 * to maintain clear separation of concerns. Signing operations are fundamentally different
 * from encryption - they prove identity rather than protect content.
 *
 * ## Implementations
 *
 * The default implementation uses secp256k1 with BIP-340 Schnorr signatures as required
 * by the Nostr protocol. Alternative implementations could support hardware security
 * modules, remote signing services (NIP-46), or other key management strategies.
 */
interface SigningPrimitives {

    /**
     * Generates a cryptographically secure random 32-byte secret key
     * that is valid for secp256k1 operations.
     */
    fun generateSecretKey(): CryptoResult<ByteString>

    /**
     * Verifies that the given 32-byte secret key is valid for secp256k1.
     * Returns true if valid, false otherwise.
     */
    fun verifySecretKey(secretKey32: ByteString): CryptoResult<Boolean>

    /**
     * Derives the x-only (32-byte) public key from a 32-byte secret key.
     * This is the format used in Nostr events (without the 02/03 prefix).
     */
    fun derivePublicKeyXOnly(secretKey32: ByteString): CryptoResult<ByteString>

    /**
     * Creates a BIP-340 Schnorr signature over a 32-byte message (typically an event ID hash).
     * Returns a 64-byte signature.
     *
     * @param secretKey32 The 32-byte secret key
     * @param message32 The 32-byte message to sign (event ID)
     * @param auxRand32 Optional 32-byte auxiliary randomness for enhanced security.
     *                  If null, random bytes will be generated.
     */
    fun schnorrSign(
        secretKey32: ByteString,
        message32: ByteString,
        auxRand32: ByteString? = null,
    ): CryptoResult<ByteString>

    /**
     * Verifies a BIP-340 Schnorr signature.
     *
     * @param signature64 The 64-byte signature
     * @param message32 The 32-byte message that was signed
     * @param publicKey32 The 32-byte x-only public key
     */
    fun schnorrVerify(
        signature64: ByteString,
        message32: ByteString,
        publicKey32: ByteString,
    ): CryptoResult<Boolean>
}
