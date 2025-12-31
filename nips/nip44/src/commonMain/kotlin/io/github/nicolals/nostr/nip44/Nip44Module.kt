package io.github.nicolals.nostr.nip44

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.nip44.model.Nip44MessageKeys
import io.github.nicolals.nostr.nip44.model.Nip44Payload
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * This module provides NIP-44 encryption/decryption helpers for payloads.
 */
class Nip44Module(
    ctx: NipModuleContext,
) : NipModule {
    override val eventUpgraders: List<EventUpgrader<out EventView>> = emptyList()
}

private const val NIP44_VERSION: Byte = 0x02
private const val NONCE_LEN = 32
private const val KEY_LEN = 32
private const val CHACHA_NONCE_LEN = 12
private const val MAC_LEN = 32
private const val MIN_PLAINTEXT_LEN = 1
private const val MAX_PLAINTEXT_LEN = 65535
private const val MIN_PADDED_LEN = 32
private const val MAX_PADDED_LEN = 65536
private const val MIN_CIPHERTEXT_LEN = MIN_PADDED_LEN + 2
private const val MAX_CIPHERTEXT_LEN = MAX_PADDED_LEN + 2
private const val MIN_BASE64_LEN = 132
private const val MAX_BASE64_LEN = 87472
private const val MIN_DECODED_LEN = 99
private const val MAX_DECODED_LEN = 65603

private val NIP44_SALT = "nip44-v2".encodeToByteArray().toByteString()
private val NIP44_VERSION_BYTES = byteArrayOf(NIP44_VERSION).toByteString()

fun NipModuleContext.nip44ConversationKey(
    privateKey32: ByteString,
    publicKey: PublicKey,
): CryptoResult<ByteString> =
    crypto.ecdhXOnly(privateKey32, publicKey.bytes).flatMap { sharedSecret ->
        if (sharedSecret.size != KEY_LEN) {
            invalidInput("sharedSecret", "32 bytes", sharedSecret.size)
        } else {
            crypto.hkdfExtractSha256(NIP44_SALT, sharedSecret)
        }
    }

fun NipModuleContext.nip44MessageKeys(
    conversationKey: ByteString,
    nonce: ByteString,
): CryptoResult<Nip44MessageKeys> {
    if (conversationKey.size != KEY_LEN) {
        return invalidInput("conversationKey", "32 bytes", conversationKey.size)
    }
    if (nonce.size != NONCE_LEN) {
        return invalidInput("nonce", "32 bytes", nonce.size)
    }
    return crypto.hkdfExpandSha256(conversationKey, nonce, 76).flatMap { okm ->
        if (okm.size != 76) {
            invalidInput("hkdfExpand", "76 bytes", okm.size)
        } else {
            val bytes = okm.toByteArray()
            val chachaKey = bytes.copyOfRange(0, KEY_LEN).toByteString()
            val chachaNonce = bytes.copyOfRange(KEY_LEN, KEY_LEN + CHACHA_NONCE_LEN).toByteString()
            val hmacKey = bytes.copyOfRange(KEY_LEN + CHACHA_NONCE_LEN, 76).toByteString()
            CryptoResult.Ok(Nip44MessageKeys(chachaKey, chachaNonce, hmacKey))
        }
    }
}

fun NipModuleContext.nip44RandomNonce(): CryptoResult<ByteString> =
    crypto.randomBytes(NONCE_LEN).flatMap { nonce ->
        if (nonce.size != NONCE_LEN) {
            invalidInput("nonce", "32 bytes", nonce.size)
        } else {
            CryptoResult.Ok(nonce)
        }
    }

fun NipModuleContext.encodeNip44Payload(payload: Nip44Payload): CryptoResult<String> =
    encodeNip44PayloadBytes(payload).flatMap { crypto.base64Encode(it) }

fun NipModuleContext.decodeNip44Payload(payload: String): CryptoResult<Nip44Payload> {
    val payloadLen = payload.length
    if (payloadLen == 0 || payload[0] == '#') {
        return CryptoResult.Err(CryptoError.Unsupported("nip44 version"))
    }
    if (payloadLen !in MIN_BASE64_LEN..MAX_BASE64_LEN) {
        return invalidInput("payload", "base64 length $MIN_BASE64_LEN..$MAX_BASE64_LEN", payloadLen)
    }
    return crypto.base64Decode(payload).flatMap { decoded ->
        val decodedLen = decoded.size
        if (decodedLen !in MIN_DECODED_LEN..MAX_DECODED_LEN) {
            return@flatMap invalidInput(
                "payload",
                "decoded length $MIN_DECODED_LEN..$MAX_DECODED_LEN",
                decodedLen,
            )
        }
        val bytes = decoded.toByteArray()
        val version = bytes[0].toInt() and 0xff
        if (version != NIP44_VERSION.toInt()) {
            return@flatMap CryptoResult.Err(CryptoError.Unsupported("nip44 version $version"))
        }
        val nonce = bytes.copyOfRange(1, 1 + NONCE_LEN).toByteString()
        val mac = bytes.copyOfRange(decodedLen - MAC_LEN, decodedLen).toByteString()
        val ciphertext = bytes.copyOfRange(1 + NONCE_LEN, decodedLen - MAC_LEN).toByteString()
        CryptoResult.Ok(Nip44Payload(nonce = nonce, ciphertext = ciphertext, mac = mac))
    }
}

fun NipModuleContext.encryptNip44(
    plaintext: String,
    senderPrivateKey: ByteString,
    recipientPublicKey: PublicKey,
): CryptoResult<Nip44Payload> =
    nip44ConversationKey(senderPrivateKey, recipientPublicKey).flatMap { conversationKey ->
        nip44RandomNonce().flatMap { nonce ->
            encryptNip44(plaintext, conversationKey, nonce)
        }
    }

fun NipModuleContext.encryptNip44(
    plaintext: String,
    conversationKey: ByteString,
    nonce: ByteString,
): CryptoResult<Nip44Payload> =
    nip44MessageKeys(conversationKey, nonce).flatMap { keys ->
        padNip44(plaintext).flatMap { padded ->
            crypto.chacha20IetfXor(keys.chachaKey, keys.chachaNonce, padded).flatMap { ciphertext ->
                hmacAad(keys.hmacKey, ciphertext, nonce).map { mac ->
                    Nip44Payload(nonce = nonce, ciphertext = ciphertext, mac = mac)
                }
            }
        }
    }

fun NipModuleContext.encryptNip44Content(
    plaintext: String,
    senderPrivateKey: ByteString,
    recipientPublicKey: PublicKey,
): CryptoResult<String> =
    encryptNip44(plaintext, senderPrivateKey, recipientPublicKey).flatMap { encodeNip44Payload(it) }

fun NipModuleContext.encryptNip44Content(
    plaintext: String,
    conversationKey: ByteString,
    nonce: ByteString,
): CryptoResult<String> =
    encryptNip44(plaintext, conversationKey, nonce).flatMap { encodeNip44Payload(it) }

fun NipModuleContext.decryptNip44(
    payload: Nip44Payload,
    receiverPrivateKey: ByteString,
    senderPublicKey: PublicKey,
): CryptoResult<String> =
    nip44ConversationKey(receiverPrivateKey, senderPublicKey).flatMap { conversationKey ->
        decryptNip44(payload, conversationKey)
    }

fun NipModuleContext.decryptNip44(
    payload: Nip44Payload,
    conversationKey: ByteString,
): CryptoResult<String> {
    val ciphertextLen = payload.ciphertext.size
    if (ciphertextLen < MIN_CIPHERTEXT_LEN || ciphertextLen > MAX_CIPHERTEXT_LEN) {
        return invalidInput(
            "ciphertext",
            "length $MIN_CIPHERTEXT_LEN..$MAX_CIPHERTEXT_LEN",
            ciphertextLen,
        )
    }
    return nip44MessageKeys(conversationKey, payload.nonce).flatMap { keys ->
        hmacAad(keys.hmacKey, payload.ciphertext, payload.nonce).flatMap { expectedMac ->
            crypto.constantTimeEquals(expectedMac, payload.mac).flatMap { matches ->
                if (!matches) {
                    CryptoResult.Err(CryptoError.DecryptionFailed("invalid MAC"))
                } else {
                    crypto.chacha20IetfXor(
                        keys.chachaKey,
                        keys.chachaNonce,
                        payload.ciphertext,
                    ).flatMap { padded ->
                        unpadNip44(padded)
                    }
                }
            }
        }
    }
}

/**
 * Callers should validate the event signature/pubkey before decrypting event content;
 * otherwise payload authenticity is not guaranteed by the outer signature.
 */
fun NipModuleContext.decryptNip44Content(
    content: String,
    receiverPrivateKey: ByteString,
    senderPublicKey: PublicKey,
): CryptoResult<String> =
    decodeNip44Payload(content).flatMap { payload ->
        decryptNip44(payload, receiverPrivateKey, senderPublicKey)
    }

fun NipModuleContext.decryptNip44Content(
    content: String,
    conversationKey: ByteString,
): CryptoResult<String> =
    decodeNip44Payload(content).flatMap { payload ->
        decryptNip44(payload, conversationKey)
    }

fun NipModuleContext.parseNip44ContentOrNull(content: String): Nip44Payload? =
    decodeNip44Payload(content).getOrNull()

private fun NipModuleContext.hmacAad(
    key: ByteString,
    message: ByteString,
    aad: ByteString,
): CryptoResult<ByteString> {
    if (aad.size != NONCE_LEN) {
        return invalidInput("aad", "32 bytes", aad.size)
    }
    return crypto.hmacSha256(key, concat(aad, message))
}

private fun encodeNip44PayloadBytes(payload: Nip44Payload): CryptoResult<ByteString> {
    if (payload.nonce.size != NONCE_LEN) {
        return invalidInput("nonce", "32 bytes", payload.nonce.size)
    }
    if (payload.mac.size != MAC_LEN) {
        return invalidInput("mac", "32 bytes", payload.mac.size)
    }
    val ciphertextLen = payload.ciphertext.size
    if (ciphertextLen !in MIN_CIPHERTEXT_LEN..MAX_CIPHERTEXT_LEN) {
        return invalidInput(
            "ciphertext",
            "length $MIN_CIPHERTEXT_LEN..$MAX_CIPHERTEXT_LEN",
            ciphertextLen,
        )
    }
    return CryptoResult.Ok(concat(NIP44_VERSION_BYTES, payload.nonce, payload.ciphertext, payload.mac))
}

private fun padNip44(plaintext: String): CryptoResult<ByteString> {
    val unpadded = plaintext.encodeToByteArray()
    val unpaddedLen = unpadded.size
    if (unpaddedLen !in MIN_PLAINTEXT_LEN..MAX_PLAINTEXT_LEN) {
        return invalidInput(
            "plaintext",
            "length $MIN_PLAINTEXT_LEN..$MAX_PLAINTEXT_LEN bytes",
            unpaddedLen,
        )
    }
    val paddedLen = calcPaddedLen(unpaddedLen)
    val output = ByteArray(2 + paddedLen)
    output[0] = ((unpaddedLen ushr 8) and 0xff).toByte()
    output[1] = (unpaddedLen and 0xff).toByte()
    unpadded.copyInto(output, 2)
    return CryptoResult.Ok(output.toByteString())
}

private fun unpadNip44(padded: ByteString): CryptoResult<String> {
    if (padded.size < 2) {
        return CryptoResult.Err(CryptoError.DecryptionFailed("invalid padding"))
    }
    val bytes = padded.toByteArray()
    val unpaddedLen = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
    if (unpaddedLen !in MIN_PLAINTEXT_LEN..MAX_PLAINTEXT_LEN) {
        return CryptoResult.Err(CryptoError.DecryptionFailed("invalid padding"))
    }
    val expectedPaddedLen = calcPaddedLen(unpaddedLen)
    val expectedTotalLen = 2 + expectedPaddedLen
    if (bytes.size != expectedTotalLen) {
        return CryptoResult.Err(CryptoError.DecryptionFailed("invalid padding"))
    }
    val unpaddedEnd = 2 + unpaddedLen
    if (unpaddedEnd > bytes.size) {
        return CryptoResult.Err(CryptoError.DecryptionFailed("invalid padding"))
    }
    for (i in unpaddedEnd until bytes.size) {
        if (bytes[i] != 0.toByte()) {
            return CryptoResult.Err(CryptoError.DecryptionFailed("invalid padding"))
        }
    }
    val unpadded = bytes.copyOfRange(2, unpaddedEnd)
    return CryptoResult.Ok(unpadded.decodeToString())
}

private fun calcPaddedLen(unpaddedLen: Int): Int {
    if (unpaddedLen <= MIN_PADDED_LEN) return MIN_PADDED_LEN
    val nextPower = 1 shl (floorLog2(unpaddedLen - 1) + 1)
    val chunk = if (nextPower <= 256) MIN_PADDED_LEN else nextPower / 8
    return chunk * (((unpaddedLen - 1) / chunk) + 1)
}

private fun floorLog2(value: Int): Int {
    var v = value
    var r = 0
    while (v > 1) {
        v = v ushr 1
        r++
    }
    return r
}

private fun concat(vararg parts: ByteString): ByteString {
    val totalLen = parts.sumOf { it.size }
    val output = ByteArray(totalLen)
    var offset = 0
    for (part in parts) {
        val bytes = part.toByteArray()
        bytes.copyInto(output, offset)
        offset += bytes.size
    }
    return output.toByteString()
}

private fun invalidInput(name: String, expected: String, actual: Int): CryptoResult<Nothing> =
    CryptoResult.Err(CryptoError.InvalidInput(name, expected, actual))
