@file:OptIn(ExperimentalUnsignedTypes::class)

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.auth.Auth
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.stream.Stream.chacha20IetfXor
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.ionspin.kotlin.crypto.util.LibsodiumUtil
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.runBlocking

private const val VERSION_V2: UByte = 0x02u
private const val MIN_PLAINTEXT_SIZE = 1
private const val MAX_PLAINTEXT_SIZE = 0xFFFF // 65535
private const val MAX_PADDING_INPUT = MAX_PLAINTEXT_SIZE + 1
private const val MIN_PADDED_LENGTH = 32
private const val NONCE_SIZE = 32
private const val CONVERSATION_KEY_SIZE = 32
private const val CHACHA_KEY_SIZE = 32
private const val CHACHA_NONCE_SIZE = 12
private const val HMAC_KEY_SIZE = 32
private const val MAC_SIZE = 32
private const val HMAC_SHA256_BLOCK_SIZE = 64
private const val MIN_PAYLOAD_BASE64 = 132
private const val MAX_PAYLOAD_BASE64 = 87472
private const val MIN_PAYLOAD_RAW = 99
private const val MAX_PAYLOAD_RAW = 65603
private const val MIN_CIPHERTEXT_SIZE = MIN_PADDED_LENGTH + 2
private val SALT = "nip44-v2".encodeToByteArray().asUByteArray()
private val BASE64_ALPHABET: Set<Char> = buildSet {
    addAll('A'..'Z')
    addAll('a'..'z')
    addAll('0'..'9')
    add('+')
    add('/')
    add('=')
}

private val ZERO = 0.toUByte()

data class MessageKeys(
    val chachaKey: UByteArray,
    val chachaNonce: UByteArray,
    val hmacKey: UByteArray,
)

data class DecodedPayload(
    val nonce: UByteArray,
    val ciphertext: UByteArray,
    val mac: UByteArray,
)

fun ensureSodium() {
    if (!LibsodiumInitializer.isInitialized()) {
        runBlocking { LibsodiumInitializer.initialize() }
    }
}

fun generateNonce(): UByteArray {
    ensureSodium()
    return LibsodiumRandom.buf(NONCE_SIZE)
}

fun hkdfExtractSha256(salt: UByteArray, ikm: UByteArray): UByteArray = hmacSha256(salt, ikm)

fun calculateConversationKey(privkey: ByteArray, pubkey: ByteArray): UByteArray {
    require(privkey.size == CONVERSATION_KEY_SIZE) { "private key must be 32 bytes" }
    require(Secp256k1.secKeyVerify(privkey)) { "invalid private key" }
    val compressed = normalizePublicKey(pubkey)
    val uncompressed = Secp256k1.pubkeyParse(compressed)
    val sharedFull = Secp256k1.pubKeyTweakMul(uncompressed, privkey)
    val sharedCompressed = Secp256k1.pubKeyCompress(sharedFull)
    val sharedX = sharedCompressed.copyOfRange(1, 33)
    return hkdfExtractSha256(SALT, sharedX.asUByteArray())
}

fun writeU16Be(n: Int): UByteArray {
    require(n in 0..0xFFFF)
    return ubyteArrayOf(
        ((n ushr 8) and 0xFF).toUByte(),
        (n and 0xFF).toUByte(),
    )
}

fun writeU8(n: Int): UByteArray {
    require(n in 0..0xFF) { "u8 out of range: $n" }
    return ubyteArrayOf(n.toUByte())
}

fun calcPaddedLen(unpaddedLen: Int): Int {
    require(unpaddedLen in MIN_PLAINTEXT_SIZE..MAX_PADDING_INPUT) { "invalid unpadded length" }
    if (unpaddedLen <= MIN_PADDED_LENGTH) return MIN_PADDED_LENGTH
    val bitLength = (unpaddedLen - 1).takeIf { it > 0 }?.let { Int.SIZE_BITS - it.countLeadingZeroBits() } ?: 0
    val nextPower = 1 shl bitLength
    val chunk = if (nextPower <= 256) MIN_PADDED_LENGTH else nextPower / 8
    return ((unpaddedLen + chunk - 1) / chunk) * chunk
}

fun pad(plaintext: String): UByteArray {
    val unpadded = plaintext.encodeToByteArray().asUByteArray()
    val unpaddedLength = unpadded.size
    require(unpaddedLength in MIN_PLAINTEXT_SIZE..MAX_PLAINTEXT_SIZE) { "invalid plaintext length" }
    val paddedLength = calcPaddedLen(unpaddedLength)
    val prefix = writeU16Be(unpaddedLength)
    val suffix = UByteArray(paddedLength - unpaddedLength)
    return prefix + unpadded + suffix
}

fun unpad(padded: UByteArray): String {
    require(padded.size >= MIN_CIPHERTEXT_SIZE) { "invalid padding" }
    val plaintextLength = readU16Be(padded)
    require(plaintextLength in MIN_PLAINTEXT_SIZE..MAX_PLAINTEXT_SIZE) { "invalid padding" }
    val expectedTotal = 2 + calcPaddedLen(plaintextLength)
    require(padded.size == expectedTotal) { "invalid padding" }
    val plaintext = padded.copyOfRange(2, 2 + plaintextLength)
    val padding = padded.copyOfRange(2 + plaintextLength, padded.size)
    require(padding.all { it == ZERO }) { "invalid padding" }
    return plaintext.asByteArray().decodeToString()
}

fun getMessageKeys(conversationKey: UByteArray, nonce: UByteArray): MessageKeys {
    require(conversationKey.size == CONVERSATION_KEY_SIZE) { "invalid conversation_key length" }
    require(nonce.size == NONCE_SIZE) { "invalid nonce length" }
    ensureSodium()
    val okm = hkdfExpandSha256(conversationKey, nonce, CHACHA_KEY_SIZE + CHACHA_NONCE_SIZE + HMAC_KEY_SIZE)
    val chachaKey = okm.copyOfRange(0, CHACHA_KEY_SIZE)
    val chachaNonce = okm.copyOfRange(CHACHA_KEY_SIZE, CHACHA_KEY_SIZE + CHACHA_NONCE_SIZE)
    val hmacKey = okm.copyOfRange(CHACHA_KEY_SIZE + CHACHA_NONCE_SIZE, okm.size)
    return MessageKeys(chachaKey, chachaNonce, hmacKey)
}

fun hmacAad(key: UByteArray, message: UByteArray, aad: UByteArray): UByteArray {
    require(key.size == HMAC_KEY_SIZE) { "invalid hmac key" }
    require(aad.size == NONCE_SIZE) { "AAD associated data must be 32 bytes" }
    ensureSodium()
    return Auth.authHmacSha256(aad + message, key)
}

fun encrypt(
    plaintext: String,
    conversationKey: UByteArray,
    nonce: UByteArray = generateNonce(),
): String {
    val messageKeys = getMessageKeys(conversationKey, nonce)
    val padded = pad(plaintext)
    val ciphertext = chacha20IetfXor(padded, messageKeys.chachaNonce, messageKeys.chachaKey)
    val mac = hmacAad(messageKeys.hmacKey, ciphertext, nonce)
    return LibsodiumUtil.toBase64(writeU8(VERSION_V2.toInt()) + nonce + ciphertext + mac)
}

fun decodePayload(payload: String): DecodedPayload {
    require(payload.isNotEmpty()) { "invalid payload length: 0" }
    require(payload[0] != '#') { "unknown encryption version" }
    require(payload.length in MIN_PAYLOAD_BASE64..MAX_PAYLOAD_BASE64) {
        "invalid payload length: ${payload.length}"
    }
    require(payload.all { it.code < 128 && BASE64_ALPHABET.contains(it) }) { "invalid base64" }
    val raw = try {
        LibsodiumUtil.fromBase64(payload)
    } catch (ex: Throwable) {
        throw IllegalArgumentException("invalid base64", ex)
    }
    require(raw.size in MIN_PAYLOAD_RAW..MAX_PAYLOAD_RAW) {
        "invalid payload length: ${raw.size}"
    }
    require(raw[0] == VERSION_V2) { "unknown encryption version ${raw[0].toInt()}" }
    require(raw.size >= 1 + NONCE_SIZE + MAC_SIZE + MIN_CIPHERTEXT_SIZE) { "payload too small" }
    val nonce = raw.copyOfRange(1, 1 + NONCE_SIZE)
    val mac = raw.copyOfRange(raw.size - MAC_SIZE, raw.size)
    val ciphertext = raw.copyOfRange(1 + NONCE_SIZE, raw.size - MAC_SIZE)
    require(ciphertext.size >= MIN_CIPHERTEXT_SIZE) { "invalid ciphertext size" }
    return DecodedPayload(nonce, ciphertext, mac)
}

fun decrypt(payload: String, conversationKey: UByteArray): String {
    val decoded = decodePayload(payload)
    val messageKeys = getMessageKeys(conversationKey, decoded.nonce)
    val calculatedMac = hmacAad(messageKeys.hmacKey, decoded.ciphertext, decoded.nonce)
    require(LibsodiumUtil.memcmp(calculatedMac, decoded.mac)) { "invalid MAC" }
    val paddedPlaintext = chacha20IetfXor(decoded.ciphertext, messageKeys.chachaNonce, messageKeys.chachaKey)
    return unpad(paddedPlaintext)
}

private fun readU16Be(source: UByteArray, offset: Int = 0): Int {
    require(source.size >= offset + 2) { "missing length prefix" }
    val hi = source[offset].toInt() and 0xFF
    val lo = source[offset + 1].toInt() and 0xFF
    return (hi shl 8) or lo
}

private fun normalizePublicKey(pubkey: ByteArray): ByteArray {
    return when (pubkey.size) {
        32 -> byteArrayOf(0x02) + pubkey
        33 -> {
            require(pubkey[0].toInt() == 2 || pubkey[0].toInt() == 3) { "compressed public key expected" }
            pubkey
        }
        65 -> Secp256k1.pubKeyCompress(pubkey)
        else -> throw IllegalArgumentException("public key must be 32, 33, or 65 bytes")
    }
}

private fun hkdfExpandSha256(prk: UByteArray, info: UByteArray, outLen: Int): UByteArray {
    require(prk.size == CONVERSATION_KEY_SIZE) { "PRK must be 32 bytes" }
    require(outLen in 1..(255 * CONVERSATION_KEY_SIZE)) { "HKDF output too large" }
    ensureSodium()
    var previousBlock = UByteArray(0)
    val output = UByteArray(outLen)
    var written = 0
    var counter = 1
    while (written < outLen) {
        val block = UByteArray(previousBlock.size + info.size + 1)
        previousBlock.copyInto(block, 0)
        info.copyInto(block, previousBlock.size)
        block[block.lastIndex] = counter.toUByte()
        val digest = hmacSha256(prk, block)
        val take = minOf(digest.size, outLen - written)
        digest.copyInto(output, destinationOffset = written, startIndex = 0, endIndex = take)
        previousBlock = digest
        written += take
        counter++
    }
    return output
}

private fun hmacSha256(key: UByteArray, message: UByteArray): UByteArray {
    ensureSodium()
    var actualKey = key
    if (actualKey.size > HMAC_SHA256_BLOCK_SIZE) {
        actualKey = Hash.sha256(actualKey)
    }
    val keyBlock = UByteArray(HMAC_SHA256_BLOCK_SIZE)
    actualKey.copyInto(keyBlock, endIndex = actualKey.size)

    val innerPad = UByteArray(HMAC_SHA256_BLOCK_SIZE)
    val outerPad = UByteArray(HMAC_SHA256_BLOCK_SIZE)
    for (i in 0 until HMAC_SHA256_BLOCK_SIZE) {
        val b = keyBlock[i].toInt() and 0xFF
        innerPad[i] = (b xor 0x36).toUByte()
        outerPad[i] = (b xor 0x5c).toUByte()
    }

    val innerInput = UByteArray(HMAC_SHA256_BLOCK_SIZE + message.size)
    innerPad.copyInto(innerInput)
    message.copyInto(innerInput, destinationOffset = HMAC_SHA256_BLOCK_SIZE)
    val innerHash = Hash.sha256(innerInput)

    val outerInput = UByteArray(HMAC_SHA256_BLOCK_SIZE + innerHash.size)
    outerPad.copyInto(outerInput)
    innerHash.copyInto(outerInput, destinationOffset = HMAC_SHA256_BLOCK_SIZE)
    return Hash.sha256(outerInput)
}
