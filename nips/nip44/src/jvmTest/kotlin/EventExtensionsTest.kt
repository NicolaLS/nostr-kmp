@file:OptIn(ExperimentalUnsignedTypes::class)

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import nostr.core.model.Event
import nostr.core.model.UnsignedEvent

class EventExtensionsTest {

    private val sec1 = "0000000000000000000000000000000000000000000000000000000000000001"
    private val sec2 = "0000000000000000000000000000000000000000000000000000000000000002"
    private val senderPub = deriveXOnly(sec1.hexToByteArray()).asUByteArray().toHexString()
    private val recipientPub = deriveXOnly(sec2.hexToByteArray()).asUByteArray().toHexString()

    @BeforeTest
    fun initSodium() {
        ensureSodium()
    }

    @Test
    fun encryptDecryptWithAutoPrefersNip44() {
        val unsigned = UnsignedEvent(
            pubkey = senderPub,
            createdAt = 1700000000,
            kind = 14,
            tags = listOf(listOf("p", recipientPub)),
            content = "hello nip44"
        )

        val encrypted = unsigned.encryptWith(senderPrivateKeyHex = sec1)
        assertNotEquals(unsigned.content, encrypted.content)

        val event = Event(
            id = "0".repeat(64),
            pubkey = senderPub,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = encrypted.content,
            sig = "0".repeat(128)
        )

        val decrypted = event.decryptWith(recipientPrivateKeyHex = sec2)
        assertEquals(unsigned.content, decrypted)
    }

    @Test
    fun kind4RequiresLegacyEncryptorInAutoMode() {
        val unsigned = UnsignedEvent(
            pubkey = senderPub,
            createdAt = 1700000001,
            kind = 4,
            tags = listOf(listOf("p", recipientPub)),
            content = "legacy dm"
        )

        assertFailsWith<UnsupportedOperationException> {
            unsigned.encryptWith(senderPrivateKeyHex = sec1)
        }

        val legacyCipher = "nip04-cipher"
        val encrypted = unsigned.encryptWith(
            senderPrivateKeyHex = sec1,
            preference = EncryptionPreference.Auto,
            legacyEncryptor = { _, _, _ -> legacyCipher }
        )
        assertEquals(legacyCipher, encrypted.content)
    }

    @Test
    fun legacyDecryptorFallbackIsInvoked() {
        val ciphertext = "legacy?iv=c29tZS1pdi0xMjM="
        val event = Event(
            id = "f".repeat(64),
            pubkey = senderPub,
            createdAt = 1700000002,
            kind = 4,
            tags = listOf(listOf("p", recipientPub)),
            content = ciphertext,
            sig = "e".repeat(128)
        )

        assertFailsWith<UnsupportedOperationException> {
            event.decryptWith(recipientPrivateKeyHex = sec2)
        }

        val plaintext = "hello legacy"
        val decrypted = event.decryptWith(
            recipientPrivateKeyHex = sec2,
            legacyDecryptor = { _, _, _ -> plaintext }
        )
        assertEquals(plaintext, decrypted)
    }
}
