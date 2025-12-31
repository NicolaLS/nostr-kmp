package io.github.nicolals.nostr.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.stream.Stream
import com.ionspin.kotlin.crypto.util.Base64Variants
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.ionspin.kotlin.crypto.util.LibsodiumUtil
import fr.acinq.secp256k1.Secp256k1
import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.crypto.CryptoResult
import okio.ByteString
import okio.ByteString.Companion.toByteString

object NostrCrypto : CryptoPrimitives {
    private const val HASH_LEN = 32
    private const val HKDF_MAX_LEN = HASH_LEN * 255
    private const val AES_BLOCK_SIZE = 16

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun randomBytes(length: Int): CryptoResult<ByteString> {
        if (length < 0) return invalidInput("length", ">= 0", length)
        return withSodium("randomBytes") {
            LibsodiumRandom.buf(length).toByteString()
        }
    }

    override fun ecdhXOnly(privateKey32: ByteString, publicKeyBytes: ByteString): CryptoResult<ByteString> {
        if (privateKey32.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidPrivateKey("expected 32 bytes, got ${privateKey32.size}"),
            )
        }
        val pubKey = publicKeyBytes.toByteArray()
        val normalizedPubKey = when (pubKey.size) {
            32 -> ByteArray(33).apply {
                this[0] = 0x02
                pubKey.copyInto(this, 1)
            }

            33, 65 -> pubKey
            else -> {
                return CryptoResult.Err(
                    CryptoError.InvalidPublicKey("expected 32, 33, or 65 bytes, got ${pubKey.size}"),
                )
            }
        }
        val privKey = privateKey32.toByteArray()
        return runProvider("ecdhXOnly") {
            val sharedPubKey = Secp256k1.pubKeyTweakMul(normalizedPubKey, privKey)
            if (sharedPubKey.size < 33) {
                throw IllegalStateException("unexpected shared public key size: ${sharedPubKey.size}")
            }
            sharedPubKey.copyOfRange(1, 33).toByteString()
        }
    }

    override fun sha256(data: ByteString): CryptoResult<ByteString> =
        withSodium("sha256") { sha256Bytes(data.toByteArray()).toByteString() }

    override fun hmacSha256(key: ByteString, data: ByteString): CryptoResult<ByteString> =
        withSodium("hmacSha256") { hmacSha256Bytes(key.toByteArray(), data.toByteArray()).toByteString() }

    override fun hkdfExtractSha256(salt: ByteString, ikm: ByteString): CryptoResult<ByteString> =
        withSodium("hkdfExtractSha256") {
            val saltBytes = salt.toByteArray()
            val ikmBytes = ikm.toByteArray()
            val actualSalt = if (saltBytes.isEmpty()) ByteArray(HASH_LEN) else saltBytes
            hmacSha256Bytes(actualSalt, ikmBytes).toByteString()
        }

    override fun hkdfExpandSha256(prk: ByteString, info: ByteString, length: Int): CryptoResult<ByteString> {
        if (length < 0) return invalidInput("length", ">= 0", length)
        if (length == 0) return CryptoResult.Ok(ByteString.EMPTY)
        if (length > HKDF_MAX_LEN) return invalidInput("length", "0..$HKDF_MAX_LEN", length)
        val prkBytes = prk.toByteArray()
        val infoBytes = info.toByteArray()
        val blocks = (length + HASH_LEN - 1) / HASH_LEN
        return withSodium("hkdfExpandSha256") {
            val output = ByteArray(blocks * HASH_LEN)
            var t = ByteArray(0)
            var offset = 0
            for (i in 1..blocks) {
                val data = ByteArray(t.size + infoBytes.size + 1)
                t.copyInto(data, 0)
                infoBytes.copyInto(data, t.size)
                data[data.size - 1] = i.toByte()
                t = hmacSha256Bytes(prkBytes, data)
                t.copyInto(output, offset)
                offset += t.size
            }
            output.copyOfRange(0, length).toByteString()
        }
    }

    override fun aes256CbcEncrypt(
        key32: ByteString,
        iv16: ByteString,
        plaintext: ByteString,
    ): CryptoResult<ByteString> {
        if (key32.size != 32) return invalidInput("key32", "32 bytes", key32.size)
        if (iv16.size != AES_BLOCK_SIZE) return invalidInput("iv16", "16 bytes", iv16.size)
        return runProvider("aes256CbcEncrypt") {
            Aes256Cbc.encrypt(
                key32.toByteArray(),
                iv16.toByteArray(),
                plaintext.toByteArray(),
            ).toByteString()
        }
    }

    override fun aes256CbcDecrypt(
        key32: ByteString,
        iv16: ByteString,
        ciphertext: ByteString,
    ): CryptoResult<ByteString> {
        if (key32.size != 32) return invalidInput("key32", "32 bytes", key32.size)
        if (iv16.size != AES_BLOCK_SIZE) return invalidInput("iv16", "16 bytes", iv16.size)
        if (ciphertext.size == 0) return invalidInput("ciphertext", "non-empty", ciphertext.size)
        if (ciphertext.size % AES_BLOCK_SIZE != 0) {
            return invalidInput("ciphertext", "multiple of 16 bytes", ciphertext.size)
        }
        return runDecrypt {
            Aes256Cbc.decrypt(
                key32.toByteArray(),
                iv16.toByteArray(),
                ciphertext.toByteArray(),
            ).toByteString()
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun chacha20IetfXor(
        key32: ByteString,
        nonce12: ByteString,
        input: ByteString,
    ): CryptoResult<ByteString> {
        if (key32.size != 32) return invalidInput("key32", "32 bytes", key32.size)
        if (nonce12.size != 12) return invalidInput("nonce12", "12 bytes", nonce12.size)
        return withSodium("chacha20IetfXor") {
            val output = Stream.chacha20IetfXor(
                input.toByteArray().toUByteArray(),
                nonce12.toByteArray().toUByteArray(),
                key32.toByteArray().toUByteArray(),
            )
            output.toByteString()
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun base64Encode(data: ByteString): CryptoResult<String> =
        withSodium("base64Encode") {
            LibsodiumUtil.toBase64(data.toByteArray().toUByteArray(), Base64Variants.ORIGINAL)
        }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun base64Decode(data: String): CryptoResult<ByteString> {
        return when (val init = ensureSodium()) {
            is CryptoResult.Err -> CryptoResult.Err(init.error)
            is CryptoResult.Ok -> try {
                CryptoResult.Ok(LibsodiumUtil.fromBase64(data, Base64Variants.ORIGINAL).toByteString())
            } catch (e: Exception) {
                CryptoResult.Err(CryptoError.DecodeError("base64"))
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun constantTimeEquals(a: ByteString, b: ByteString): CryptoResult<Boolean> {
        if (a.size != b.size) return CryptoResult.Ok(false)
        return withSodium("constantTimeEquals") {
            LibsodiumUtil.memcmp(a.toByteArray().toUByteArray(), b.toByteArray().toUByteArray())
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun sha256Bytes(data: ByteArray): ByteArray =
        Hash.sha256(data.toUByteArray()).toByteArray()

    private fun hmacSha256Bytes(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        var actualKey = key
        if (actualKey.size > blockSize) {
            actualKey = sha256Bytes(actualKey)
        }
        if (actualKey.size < blockSize) {
            val padded = ByteArray(blockSize)
            actualKey.copyInto(padded)
            actualKey = padded
        }
        val oKeyPad = ByteArray(blockSize)
        val iKeyPad = ByteArray(blockSize)
        for (i in 0 until blockSize) {
            val keyByte = actualKey[i]
            oKeyPad[i] = (keyByte.toInt() xor 0x5c).toByte()
            iKeyPad[i] = (keyByte.toInt() xor 0x36).toByte()
        }
        val inner = sha256Bytes(iKeyPad + data)
        return sha256Bytes(oKeyPad + inner)
    }

    private fun ensureSodium(): CryptoResult<Unit> {
        if (LibsodiumInitializer.isInitialized()) return CryptoResult.Ok(Unit)
        return try {
            LibsodiumInitializer.initializeWithCallback {}
            if (LibsodiumInitializer.isInitialized()) {
                CryptoResult.Ok(Unit)
            } else {
                CryptoResult.Err(CryptoError.InitializationFailed("libsodium"))
            }
        } catch (e: Exception) {
            CryptoResult.Err(CryptoError.InitializationFailed("libsodium", e.message))
        }
    }

    private fun invalidInput(name: String, expected: String, actual: Int): CryptoResult<Nothing> =
        CryptoResult.Err(CryptoError.InvalidInput(name, expected, actual))

    private inline fun <T> withSodium(operation: String, block: () -> T): CryptoResult<T> {
        return when (val init = ensureSodium()) {
            is CryptoResult.Err -> CryptoResult.Err(init.error)
            is CryptoResult.Ok -> runProvider(operation, block)
        }
    }

    private inline fun <T> runProvider(operation: String, block: () -> T): CryptoResult<T> =
        try {
            CryptoResult.Ok(block())
        } catch (e: Exception) {
            CryptoResult.Err(CryptoError.ProviderError(operation, e.message))
        }

    private inline fun <T> runDecrypt(block: () -> T): CryptoResult<T> =
        try {
            CryptoResult.Ok(block())
        } catch (e: Exception) {
            CryptoResult.Err(CryptoError.DecryptionFailed(e.message))
        }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun UByteArray.toByteString(): ByteString =
        toByteArray().toByteString()
}
