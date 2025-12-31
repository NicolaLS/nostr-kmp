package io.github.nicolals.nostr.core.crypto

import okio.ByteString

/**
 * Minimal crypto primitives needed by multiple NIP implementations.
 * Names are generic to keep the interface reusable across NIPs.
 */
interface CryptoPrimitives {
    /** Cryptographically secure random bytes. */
    fun randomBytes(length: Int): CryptoResult<ByteString>

    /**
     * secp256k1 ECDH that returns the x-only 32-byte shared secret.
     * publicKeyBytes may be x-only (32), compressed (33), or uncompressed (65).
     */
    fun ecdhXOnly(privateKey32: ByteString, publicKeyBytes: ByteString): CryptoResult<ByteString>

    /** SHA-256 hash. */
    fun sha256(data: ByteString): CryptoResult<ByteString>

    /** HMAC-SHA256 (32-byte output). */
    fun hmacSha256(key: ByteString, data: ByteString): CryptoResult<ByteString>

    /** HKDF extract with SHA-256 (32-byte PRK). */
    fun hkdfExtractSha256(salt: ByteString, ikm: ByteString): CryptoResult<ByteString>

    /** HKDF expand with SHA-256 (length <= 255 * 32). */
    fun hkdfExpandSha256(prk: ByteString, info: ByteString, length: Int): CryptoResult<ByteString>

    /** AES-256-CBC with PKCS7 padding. */
    fun aes256CbcEncrypt(key32: ByteString, iv16: ByteString, plaintext: ByteString): CryptoResult<ByteString>
    fun aes256CbcDecrypt(key32: ByteString, iv16: ByteString, ciphertext: ByteString): CryptoResult<ByteString>

    /** ChaCha20-IETF stream XOR (same function for enc/dec). */
    fun chacha20IetfXor(key32: ByteString, nonce12: ByteString, input: ByteString): CryptoResult<ByteString>

    /** Base64 encode/decode (RFC 4648 with padding). */
    fun base64Encode(data: ByteString): CryptoResult<String>
    fun base64Decode(data: String): CryptoResult<ByteString>

    /** Constant-time comparison for MAC verification. */
    fun constantTimeEquals(a: ByteString, b: ByteString): CryptoResult<Boolean>
}
