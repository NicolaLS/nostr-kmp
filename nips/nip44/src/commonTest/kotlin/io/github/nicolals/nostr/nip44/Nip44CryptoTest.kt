package io.github.nicolals.nostr.nip44

import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrSigning
import io.github.nicolals.nostr.nip44.model.Nip44Payload
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Nip44CryptoTest {
    private val conversationKey = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".decodeHex()
    private val nonce = "01".padStart(64, '0').decodeHex()

    @Test
    fun officialConversationAndMessageKeyVectors() {
        for (vector in Nip44Vectors.conversations) {
            assertEquals(vector.conversation_key, NostrCrypto.nip44ConversationKey(
                vector.sec1.decodeHex(), PublicKey.fromHex(vector.pub2),
            ).getOrThrow().hex())
        }
        for (vector in Nip44Vectors.messageKeys) {
            val keys = NostrCrypto.nip44MessageKeys(
                Nip44Vectors.messageConversationKey.decodeHex(), vector.nonce.decodeHex(),
            ).getOrThrow()
            assertEquals(vector.chacha_key, keys.chachaKey.hex())
            assertEquals(vector.chacha_nonce, keys.chachaNonce.hex())
            assertEquals(vector.hmac_key, keys.hmacKey.hex())
            assertFalse(keys.toString().contains(keys.chachaKey.hex()))
            assertFalse(keys.toString().contains(keys.hmacKey.hex()))
        }
    }

    @Test
    fun officialEncryptDecryptVectorsAndEcdhSymmetry() {
        for (vector in Nip44Vectors.messages) {
            val firstPublicKey = PublicKey.fromBytes(NostrSigning.derivePublicKeyXOnly(vector.sec1.decodeHex()).getOrThrow())
            val secondPublicKey = PublicKey.fromBytes(NostrSigning.derivePublicKeyXOnly(vector.sec2.decodeHex()).getOrThrow())
            val firstKey = NostrCrypto.nip44ConversationKey(vector.sec1.decodeHex(), secondPublicKey).getOrThrow()
            val secondKey = NostrCrypto.nip44ConversationKey(vector.sec2.decodeHex(), firstPublicKey).getOrThrow()
            assertEquals(vector.conversation_key, firstKey.hex())
            assertEquals(firstKey, secondKey)
            assertEquals(vector.payload, NostrCrypto.encryptNip44Content(
                vector.plaintext, firstKey, vector.nonce.decodeHex(),
            ).getOrThrow())
            assertEquals(vector.plaintext, NostrCrypto.decryptNip44Content(vector.payload, secondKey).getOrThrow())
        }
    }

    @Test
    fun officialLongMessageVectorsAndPaddingBoundaries() {
        for (vector in Nip44Vectors.longMessages) {
            val plaintext = vector.pattern.repeat(vector.repeat.toInt())
            assertEquals(vector.plaintext_sha256, plaintext.encodeUtf8().sha256().hex())
            val encrypted = NostrCrypto.encryptNip44Content(
                plaintext, vector.conversation_key.decodeHex(), vector.nonce.decodeHex(),
            ).getOrThrow()
            assertEquals(vector.payload_sha256, encrypted.encodeUtf8().sha256().hex())
            assertEquals(plaintext, NostrCrypto.decryptNip44Content(encrypted, vector.conversation_key.decodeHex()).getOrThrow())
        }
        for ((length, paddedLength) in Nip44Vectors.padding) {
            val plaintext = "a".repeat(length)
            val encrypted = NostrCrypto.encryptNip44(plaintext, conversationKey, nonce).getOrThrow()
            assertEquals(paddedLength + 2, encrypted.ciphertext.size)
            assertEquals(plaintext, NostrCrypto.decryptNip44(encrypted, conversationKey).getOrThrow())
        }
    }

    @Test
    fun officialInvalidConversationKeysAndPayloadsAreRejected() {
        for (vector in Nip44Vectors.invalidConversations) {
            assertTrue(runCatching {
                NostrCrypto.nip44ConversationKey(vector.sec1.decodeHex(), PublicKey.fromHex(vector.pub2)).getOrThrow()
            }.isFailure, vector.note)
        }
        for (vector in Nip44Vectors.invalidPayloads) {
            assertIs<CryptoResult.Err>(NostrCrypto.decryptNip44Content(
                vector.payload, vector.conversation_key.decodeHex(),
            ), vector.note)
        }
    }

    @Test
    fun resourceLimitsAndInvalidInputsFailBeforeDecoding() {
        val noDecoder = object : CryptoPrimitives by NostrCrypto {
            override fun base64Decode(data: String): CryptoResult<ByteString> = error("decoder must not run")
        }
        assertIs<CryptoResult.Err>(noDecoder.decodeNip44Payload("A".repeat(87476)))
        for (length in listOf(0, 65536, 100000)) {
            assertIs<CryptoResult.Err>(NostrCrypto.encryptNip44("a".repeat(length), conversationKey, nonce))
        }
        assertIs<CryptoResult.Err>(NostrCrypto.encryptNip44("a", ByteString.EMPTY, nonce))
        assertIs<CryptoResult.Err>(NostrCrypto.encryptNip44("a", conversationKey, ByteString.EMPTY))
        assertIs<CryptoResult.Err>(NostrCrypto.encryptNip44("\uD800", conversationKey, nonce))
    }

    @Test
    fun invalidMacNeverReachesStreamDecryption() {
        val payload = NostrCrypto.encryptNip44("secret", conversationKey, nonce).getOrThrow()
        val noDecrypt = object : CryptoPrimitives by NostrCrypto {
            override fun chacha20IetfXor(key32: ByteString, nonce12: ByteString, input: ByteString): CryptoResult<ByteString> =
                error("unauthenticated ciphertext must not be decrypted")
        }
        for (tampered in listOf(
            payload.copy(nonce = flip(payload.nonce)),
            payload.copy(ciphertext = flip(payload.ciphertext)),
            payload.copy(mac = flip(payload.mac)),
            payload.copy(mac = ByteString.EMPTY),
        )) assertIs<CryptoResult.Err>(noDecrypt.decryptNip44(tampered, conversationKey))
    }

    @Test
    fun authenticatedMalformedUtf8IsRejected() {
        val keys = NostrCrypto.nip44MessageKeys(conversationKey, nonce).getOrThrow()
        val malformed = ByteArray(34).apply { this[1] = 1; this[2] = 0xff.toByte() }.toByteString()
        val ciphertext = NostrCrypto.chacha20IetfXor(keys.chachaKey, keys.chachaNonce, malformed).getOrThrow()
        val mac = NostrCrypto.hmacSha256(keys.hmacKey, Buffer().write(nonce).write(ciphertext).readByteString()).getOrThrow()
        assertIs<CryptoResult.Err>(NostrCrypto.decryptNip44(Nip44Payload(nonce, ciphertext, mac), conversationKey))
    }

    private fun flip(bytes: ByteString): ByteString = bytes.toByteArray().apply {
        this[0] = (this[0].toInt() xor 1).toByte()
    }.toByteString()
}
