@file:OptIn(ExperimentalEncodingApi::class)

import fr.acinq.secp256k1.Secp256k1
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import nostr.core.utils.hexToByteArray

private const val KEY_SIZE_BYTES = 32
private const val IV_SIZE_BYTES = 16
private const val SEPARATOR = "?iv="

private val base64 = Base64.Default

/**
 * Encrypts [plaintext] using the legacy NIP-04 scheme.
 *
 * The [senderPrivateKeyHex] must be a 32-byte secret key encoded as 64 lowercase hex characters.
 * The [recipientPubkeyHex] accepts x-only (32-byte), compressed (33-byte), or uncompressed (65-byte) encodings.
 */
fun encrypt(
    plaintext: String,
    senderPrivateKeyHex: String,
    recipientPubkeyHex: String,
    iv: ByteArray = generateInitializationVector(),
): String {
    val senderPrivKey = senderPrivateKeyHex.requireHexKey(KEY_SIZE_BYTES, "sender private key")
    val recipientPubKey = recipientPubkeyHex.requirePublicKeyBytes()
    val sharedSecret = deriveSharedSecret(senderPrivKey, recipientPubKey)
    return encryptWithSharedSecret(plaintext, sharedSecret, iv)
}

/**
 * Decrypts the legacy NIP-04 [payload] using the receiver's private key and the sender's public key.
 */
fun decrypt(
    payload: String,
    recipientPrivateKeyHex: String,
    senderPubkeyHex: String,
): String {
    val recipientPrivKey = recipientPrivateKeyHex.requireHexKey(KEY_SIZE_BYTES, "recipient private key")
    val senderPubKey = senderPubkeyHex.requirePublicKeyBytes()
    val sharedSecret = deriveSharedSecret(recipientPrivKey, senderPubKey)
    return decryptWithSharedSecret(payload, sharedSecret)
}

/**
 * Encrypts [plaintext] with a pre-computed NIP-04 [sharedSecret].
 *
 * @param plaintext UTF-8 text to encrypt.
 * @param sharedSecret raw 32-byte shared secret derived from secp256k1 ECDH (x-coordinate, no hashing).
 * @param iv 16-byte initialization vector. When not provided, a secure random IV is generated.
 */
fun encryptWithSharedSecret(
    plaintext: String,
    sharedSecret: ByteArray,
    iv: ByteArray = generateInitializationVector(),
): String {
    require(sharedSecret.size == KEY_SIZE_BYTES) { "shared secret must be 32 bytes" }
    require(iv.size == IV_SIZE_BYTES) { "iv must be 16 bytes" }
    val ciphertext = aes256CbcEncrypt(sharedSecret, iv, plaintext.encodeToByteArray())
    val cipherBase64 = base64.encode(ciphertext)
    val ivBase64 = base64.encode(iv)
    return buildString(cipherBase64.length + SEPARATOR.length + ivBase64.length) {
        append(cipherBase64)
        append(SEPARATOR)
        append(ivBase64)
    }
}

/**
 * Decrypts a NIP-04 [payload] using a pre-computed shared secret.
 *
 * @param payload string produced by [encrypt] or [encryptWithSharedSecret].
 * @param sharedSecret raw 32-byte shared secret derived from secp256k1 ECDH (x-coordinate, no hashing).
 */
fun decryptWithSharedSecret(
    payload: String,
    sharedSecret: ByteArray,
): String {
    require(sharedSecret.size == KEY_SIZE_BYTES) { "shared secret must be 32 bytes" }
    val (cipherBytes, ivBytes) = payload.parse()
    val plaintextBytes = aes256CbcDecrypt(sharedSecret, ivBytes, cipherBytes)
    return plaintextBytes.decodeToString()
}

/**
 * Generates a random 16-byte initialization vector suitable for AES-CBC.
 */
fun generateInitializationVector(): ByteArray = secureRandomBytes(IV_SIZE_BYTES)

/**
 * Derives the raw 32-byte shared secret (x-coordinate of the secp256k1 ECDH shared point).
 *
 * @param privateKey caller's 32-byte secret key.
 * @param publicKey counterparty's public key (accepts x-only, compressed, or uncompressed encodings).
 */
fun deriveSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    require(privateKey.size == KEY_SIZE_BYTES) { "private key must be 32 bytes" }
    require(Secp256k1.secKeyVerify(privateKey)) { "invalid private key" }
    val normalizedPub = normalizePublicKey(publicKey)
    val parsed = Secp256k1.pubkeyParse(normalizedPub)
    val shared = Secp256k1.pubKeyTweakMul(parsed, privateKey)
    val compressed = Secp256k1.pubKeyCompress(shared)
    return compressed.copyOfRange(1, 33)
}

private fun String.requireHexKey(expectedBytes: Int, label: String): ByteArray {
    val bytes = decodeHex(label)
    require(bytes.size == expectedBytes) { "$label must decode to $expectedBytes bytes but was ${bytes.size}" }
    return bytes
}

private fun String.requirePublicKeyBytes(): ByteArray {
    val bytes = decodeHex("public key")
    require(bytes.size == 32 || bytes.size == 33 || bytes.size == 65) {
        "public key must decode to 32, 33, or 65 bytes but was ${bytes.size}"
    }
    return bytes
}

private fun String.decodeHex(label: String): ByteArray = try {
    hexToByteArray()
} catch (ex: IllegalArgumentException) {
    throw IllegalArgumentException("$label is not valid hex", ex)
}

private fun normalizePublicKey(pubkey: ByteArray): ByteArray = when (pubkey.size) {
    32 -> byteArrayOf(0x02) + pubkey
    33 -> {
        require(pubkey[0].toInt() == 2 || pubkey[0].toInt() == 3) { "compressed public key expected" }
        pubkey
    }
    65 -> Secp256k1.pubKeyCompress(pubkey)
    else -> throw IllegalArgumentException("public key must be 32, 33, or 65 bytes")
}

private fun String.parse(): Pair<ByteArray, ByteArray> {
    val separatorIndex = indexOf(SEPARATOR)
    require(separatorIndex > 0 && separatorIndex < lastIndex) { "ciphertext must contain '$SEPARATOR'" }
    val cipherPart = substring(0, separatorIndex)
    val ivPart = substring(separatorIndex + SEPARATOR.length)
    require(cipherPart.isNotEmpty()) { "ciphertext payload is empty" }
    require(ivPart.isNotEmpty()) { "iv payload is empty" }
    val cipherBytes = cipherPart.decodeBase64("ciphertext")
    val ivBytes = ivPart.decodeBase64("iv")
    require(ivBytes.size == IV_SIZE_BYTES) { "iv must decode to 16 bytes but was ${ivBytes.size}" }
    require(cipherBytes.isNotEmpty() && cipherBytes.size % IV_SIZE_BYTES == 0) {
        "ciphertext length must be a positive multiple of 16 bytes"
    }
    return cipherBytes to ivBytes
}

private fun String.decodeBase64(label: String): ByteArray = try {
    base64.decode(this)
} catch (ex: IllegalArgumentException) {
    throw IllegalArgumentException("$label is not valid base64", ex)
}

internal expect fun aes256CbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray
internal expect fun aes256CbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray
internal expect fun secureRandomBytes(length: Int): ByteArray
