package io.github.nicolals.nostr.crypto

import io.github.nicolals.nostr.core.crypto.CryptoResult
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CryptoPrimitivesTest {
    @Test
    fun sha256AndHmacKnownAnswers() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", NostrCrypto.sha256("abc".encodeUtf8()).getOrThrow().hex())
        // RFC 4231, test case 1.
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            NostrCrypto.hmacSha256("0b".repeat(20).decodeHex(), "Hi There".encodeUtf8()).getOrThrow().hex(),
        )
    }

    @Test
    fun hkdfRfc5869CaseOneAndLengthBounds() {
        val prk = NostrCrypto.hkdfExtractSha256(
            "000102030405060708090a0b0c".decodeHex(), "0b".repeat(22).decodeHex(),
        ).getOrThrow()
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.hex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            NostrCrypto.hkdfExpandSha256(prk, "f0f1f2f3f4f5f6f7f8f9".decodeHex(), 42).getOrThrow().hex(),
        )
        assertEquals(ByteString.EMPTY, NostrCrypto.hkdfExpandSha256(prk, ByteString.EMPTY, 0).getOrThrow())
        assertIs<CryptoResult.Err>(NostrCrypto.hkdfExpandSha256(prk, ByteString.EMPTY, -1))
        assertIs<CryptoResult.Err>(NostrCrypto.hkdfExpandSha256(prk, ByteString.EMPTY, 8161))
    }

    @Test
    fun aes256CbcNistFirstBlockAndPkcs7() {
        // NIST SP 800-38A F.2.5: the PKCS7 provider appends another block after this known block.
        val key = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".decodeHex()
        val iv = "000102030405060708090a0b0c0d0e0f".decodeHex()
        val plaintext = "6bc1bee22e409f96e93d7e117393172a".decodeHex()
        val ciphertext = NostrCrypto.aes256CbcEncrypt(key, iv, plaintext).getOrThrow()
        assertEquals("f58c4c04d6e5f1ba779eabfb5f7bfbd6", ciphertext.substring(0, 16).hex())
        assertEquals(32, ciphertext.size)
        assertEquals(plaintext, NostrCrypto.aes256CbcDecrypt(key, iv, ciphertext).getOrThrow())
        val empty = NostrCrypto.aes256CbcEncrypt(key, iv, ByteString.EMPTY).getOrThrow()
        assertEquals(16, empty.size)
        assertEquals(ByteString.EMPTY, NostrCrypto.aes256CbcDecrypt(key, iv, empty).getOrThrow())
        assertIs<CryptoResult.Err>(NostrCrypto.aes256CbcDecrypt(key, iv, ciphertext.substring(0, 15)))
        // A corrupted IV makes the last byte of the empty-message padding invalid deterministically.
        val changedIv = iv.toByteArray().apply { this[15] = (this[15].toInt() xor 1).toByte() }.toByteString()
        assertIs<CryptoResult.Err>(NostrCrypto.aes256CbcDecrypt(key, changedIv, empty))
    }

    @Test
    fun aesCbcValidatesEveryPkcs7PaddingByte() {
        val key = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".decodeHex()
        val iv = "000102030405060708090a0b0c0d0e0f".decodeHex()
        for (paddingSize in 1..16) {
            val plaintext = ByteArray(16 - paddingSize) { 0x41 }.toByteString()
            val ciphertext = NostrCrypto.aes256CbcEncrypt(key, iv, plaintext).getOrThrow()
            assertEquals(16, ciphertext.size)
            assertEquals(plaintext, NostrCrypto.aes256CbcDecrypt(key, iv, ciphertext).getOrThrow())
            for (offset in 1..paddingSize) {
                // In the first CBC block, changing the IV flips exactly the corresponding
                // plaintext byte, so this corrupts padding deterministically, not probabilistically.
                val changedIv = iv.toByteArray().apply {
                    this[16 - offset] = (this[16 - offset].toInt() xor 1).toByte()
                }.toByteString()
                assertIs<CryptoResult.Err>(
                    NostrCrypto.aes256CbcDecrypt(key, changedIv, ciphertext),
                    "padding length $paddingSize, corrupted offset $offset",
                )
            }
        }
        val empty = NostrCrypto.aes256CbcEncrypt(key, iv, ByteString.EMPTY).getOrThrow()
        for (invalidSize in listOf(0, 17, 255)) {
            val changedIv = iv.toByteArray().apply {
                this[15] = (this[15].toInt() xor 16 xor invalidSize).toByte()
            }.toByteString()
            assertIs<CryptoResult.Err>(NostrCrypto.aes256CbcDecrypt(key, changedIv, empty))
        }
    }

    @Test
    fun base64AndConstantTimeComparison() {
        assertEquals("Zm9v", NostrCrypto.base64Encode("foo".encodeUtf8()).getOrThrow())
        assertEquals("foo", NostrCrypto.base64Decode("Zm9v").getOrThrow().utf8())
        assertIs<CryptoResult.Err>(NostrCrypto.base64Decode("not base64!"))
        assertTrue(NostrCrypto.constantTimeEquals("abc".encodeUtf8(), "abc".encodeUtf8()).getOrThrow())
        assertFalse(NostrCrypto.constantTimeEquals("abc".encodeUtf8(), "abd".encodeUtf8()).getOrThrow())
        assertFalse(NostrCrypto.constantTimeEquals("abc".encodeUtf8(), ByteString.EMPTY).getOrThrow())
    }
}
