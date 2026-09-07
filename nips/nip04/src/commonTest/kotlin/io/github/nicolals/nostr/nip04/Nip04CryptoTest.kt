package io.github.nicolals.nostr.nip04

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrEventVerifier
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip04.event.EncryptedDirectMessageEvent
import io.github.nicolals.nostr.nip04.model.Nip04CipherText
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class Nip04CryptoTest {
    private val firstSecret = SecretKey.fromHex("01".padStart(64, '0'))
    private val secondSecret = SecretKey.fromHex("02".padStart(64, '0'))
    private val firstSigner = NostrSigner.fromSecretKey(firstSecret).getOrThrow()
    private val secondSigner = NostrSigner.fromSecretKey(secondSecret).getOrThrow()
    private val first: Nip04Cipher = LocalNip04Cipher(firstSecret, NostrCrypto)
    private val second: Nip04Cipher = LocalNip04Cipher(secondSecret, NostrCrypto)
    private val verifier = NostrEventVerifier()

    @Test
    fun peersRoundTripEmptyUnicodeAndBlockBoundaryMessages() = runTest {
        for (plaintext in listOf("", "a", "a".repeat(15), "a".repeat(16), "a".repeat(17), "café 🦄")) {
            val content = first.encrypt(plaintext, secondSigner.pubkey).getOrThrow()
            assertEquals(plaintext, second.decrypt(content, firstSigner.pubkey).getOrThrow())
            assertEquals(plaintext, first.decrypt(content, secondSigner.pubkey).getOrThrow())
        }
        assertNotEquals(
            first.encrypt("same", secondSigner.pubkey).getOrThrow(),
            first.encrypt("same", secondSigner.pubkey).getOrThrow(),
        )
    }

    @Test
    fun malformedContentKeysIvAndPaddingReturnErrors() {
        for (content in listOf("", "ciphertext", "?iv=", "AA==?iv=AA==", "invalid?iv=invalid")) {
            assertIs<CryptoResult.Err>(NostrCrypto.decryptNip04Content(content, secondSecret.bytes, firstSigner.pubkey))
        }
        val iv = NostrCrypto.base64Encode(ByteArray(16).toByteString()).getOrThrow()
        val invalidBlock = NostrCrypto.base64Encode(ByteArray(15).toByteString()).getOrThrow()
        assertIs<CryptoResult.Err>(NostrCrypto.decryptNip04Content("$invalidBlock?iv=$iv", secondSecret.bytes, firstSigner.pubkey))
        assertIs<CryptoResult.Err>(NostrCrypto.encryptNip04Content("hello", ByteArray(32).toByteString(), firstSigner.pubkey))
        assertIs<CryptoResult.Err>(NostrCrypto.encryptNip04Content("\uD800", firstSecret.bytes, secondSigner.pubkey))
    }

    @Test
    fun malformedUtf8IsRejectedAfterCbcDecryption() {
        val key = NostrCrypto.ecdhXOnly(firstSecret.bytes, secondSigner.pubkey.bytes).getOrThrow()
        val iv = ByteArray(16).toByteString()
        val ciphertext = NostrCrypto.aes256CbcEncrypt(key, iv, byteArrayOf(0xff.toByte()).toByteString()).getOrThrow()
        val content = Nip04CipherText(
            NostrCrypto.base64Encode(ciphertext).getOrThrow(), NostrCrypto.base64Encode(iv).getOrThrow(),
        ).toContent()
        assertIs<CryptoResult.Err>(NostrCrypto.decryptNip04Content(content, secondSecret.bytes, firstSigner.pubkey))
    }

    @Test
    fun authenticatedViewsDecryptExactEventContentAndRejectTampering() = runTest {
        val encrypted = first.encrypt("private message", secondSigner.pubkey).getOrThrow()
        val event = firstSigner.sign(eventTemplate(
            EventKind(4), encrypted, listOf(listOf("p", secondSigner.pubkey.hex)),
        )).getOrThrow().event
        val view = EncryptedDirectMessageEvent.fromEventOrNull(event)!!
        assertEquals("private message", view.decryptForRecipient(second, verifier).getOrThrow())
        assertEquals("private message", view.decryptForSender(first, verifier).getOrThrow())
        var decryptions = 0
        val recording = object : Nip04Cipher by second {
            override suspend fun decrypt(content: String, senderPublicKey: io.github.nicolals.nostr.core.primitives.PublicKey): CryptoResult<String> {
                decryptions++
                return second.decrypt(content, senderPublicKey)
            }
        }
        val tampered = event.copy(content = first.encrypt("changed", secondSigner.pubkey).getOrThrow())
        assertIs<CryptoResult.Err>(recording.decryptEvent(tampered, verifier))
        assertEquals(0, decryptions)
        // Even a manually constructed view cannot substitute an unauthenticated cached ciphertext.
        val mismatchingView = EncryptedDirectMessageEvent(
            event, view.recipientPubkey, null, Nip04CipherText.parseOrNull(tampered.content)!!,
        )
        assertEquals("private message", mismatchingView.decryptForRecipient(recording, verifier).getOrThrow())
        assertEquals(1, decryptions)
    }
}
