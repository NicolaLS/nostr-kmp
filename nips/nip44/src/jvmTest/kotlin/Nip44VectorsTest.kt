@file:OptIn(ExperimentalUnsignedTypes::class)

import com.ionspin.kotlin.crypto.hash.Hash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Nip44VectorsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val vectors = json.parseToJsonElement(readResource("nip44.vectors.json")).jsonObject
    private val v2 = vectors.getValue("v2").jsonObject
    private val valid = v2.getValue("valid").jsonObject
    private val invalid = v2.getValue("invalid").jsonObject

    @BeforeTest
    fun initSodium() {
        ensureSodium()
    }

    @Test
    fun conversationKeyVectors() {
        valid.getValue("get_conversation_key").jsonArray.forEachIndexed { index, entry ->
            val obj = entry.jsonObject
            val sec1 = obj.getValue("sec1").jsonPrimitive.content.hexToByteArray()
            val pub2 = obj.getValue("pub2").jsonPrimitive.content.hexToByteArray()
            val expected = obj.getValue("conversation_key").jsonPrimitive.content.hexToUByteArray()
            val derived = calculateConversationKey(sec1, pub2)
            assertArraysEqual(expected, derived, "conversation-key[$index]")
        }
    }

    @Test
    fun messageKeyVectors() {
        val data = valid.getValue("get_message_keys").jsonObject
        val conversationKey = data.getValue("conversation_key").jsonPrimitive.content.hexToUByteArray()
        data.getValue("keys").jsonArray.forEachIndexed { index, entry ->
            val obj = entry.jsonObject
            val nonce = obj.getValue("nonce").jsonPrimitive.content.hexToUByteArray()
            val expectedChaChaKey = obj.getValue("chacha_key").jsonPrimitive.content.hexToUByteArray()
            val expectedChaChaNonce = obj.getValue("chacha_nonce").jsonPrimitive.content.hexToUByteArray()
            val expectedHmacKey = obj.getValue("hmac_key").jsonPrimitive.content.hexToUByteArray()
            val derived = getMessageKeys(conversationKey, nonce)
            assertArraysEqual(expectedChaChaKey, derived.chachaKey, "chacha-key[$index]")
            assertArraysEqual(expectedChaChaNonce, derived.chachaNonce, "chacha-nonce[$index]")
            assertArraysEqual(expectedHmacKey, derived.hmacKey, "hmac-key[$index]")
        }
    }

    @Test
    fun paddedLengthVectors() {
        valid.getValue("calc_padded_len").jsonArray.forEachIndexed { index, entry ->
            val pair = entry.jsonArray
            val unpadded = pair[0].jsonPrimitive.int
            val expected = pair[1].jsonPrimitive.int
            val actual = calcPaddedLen(unpadded)
            assertEquals(expected, actual, "calc_padded_len[$index]")
        }
    }

    @Test
    fun encryptDecryptVectors() {
        valid.getValue("encrypt_decrypt").jsonArray.forEachIndexed { index, entry ->
            val obj = entry.jsonObject
            val sec1 = obj.getValue("sec1").jsonPrimitive.content.hexToByteArray()
            val sec2 = obj.getValue("sec2").jsonPrimitive.content.hexToByteArray()
            val pub2 = deriveXOnly(sec2)
            val pub1 = deriveXOnly(sec1)
            val expectedConversationKey = obj.getValue("conversation_key").jsonPrimitive.content.hexToUByteArray()
            val nonce = obj.getValue("nonce").jsonPrimitive.content.hexToUByteArray()
            val plaintext = obj.getValue("plaintext").jsonPrimitive.content
            val expectedPayload = obj.getValue("payload").jsonPrimitive.content

            val keyFromSec1 = calculateConversationKey(sec1, pub2)
            assertArraysEqual(expectedConversationKey, keyFromSec1, "conversation-key-forward[$index]")
            val keyFromSec2 = calculateConversationKey(sec2, pub1)
            assertArraysEqual(expectedConversationKey, keyFromSec2, "conversation-key-reverse[$index]")

            val payload = encrypt(plaintext, expectedConversationKey, nonce)
            assertEquals(expectedPayload, payload, "payload[$index]")
            val decrypted = decrypt(payload, expectedConversationKey)
            assertEquals(plaintext, decrypted, "plaintext[$index]")
        }
    }

    @Test
    fun encryptDecryptLongMessages() {
        valid.getValue("encrypt_decrypt_long_msg").jsonArray.forEachIndexed { index, entry ->
            val obj = entry.jsonObject
            val conversationKey = obj.getValue("conversation_key").jsonPrimitive.content.hexToUByteArray()
            val nonce = obj.getValue("nonce").jsonPrimitive.content.hexToUByteArray()
            val pattern = obj.getValue("pattern").jsonPrimitive.content
            val repeat = obj.getValue("repeat").jsonPrimitive.int
            val plaintext = pattern.repeat(repeat)
            val plaintextHash = obj.getValue("plaintext_sha256").jsonPrimitive.content
            val payloadHash = obj.getValue("payload_sha256").jsonPrimitive.content

            assertEquals(plaintextHash, sha256Hex(plaintext.encodeToByteArray()), "plaintext-sha[$index]")
            val payload = encrypt(plaintext, conversationKey, nonce)
            assertEquals(payloadHash, sha256Hex(payload.encodeToByteArray()), "payload-sha[$index]")
            val decrypted = decrypt(payload, conversationKey)
            assertEquals(plaintext, decrypted, "long-plaintext[$index]")
        }
    }
    @Test
    fun invalidEncryptLengths() {
        invalid.getValue("encrypt_msg_lengths").jsonArray.forEachIndexed { index, entry ->
            val length = entry.jsonPrimitive.int
            assertFailsWith<IllegalArgumentException>("length[$index]") {
                encrypt("a".repeat(length), UByteArray(32), UByteArray(32))
            }
        }
    }

    @Test
    fun invalidDecryptVectors() {
        invalid.getValue("decrypt").jsonArray.forEachIndexed { index, entry ->
            val obj = entry.jsonObject
            val conversationKey = obj.getValue("conversation_key").jsonPrimitive.content.hexToUByteArray()
            val payload = obj.getValue("payload").jsonPrimitive.content
            val note = obj.getValue("note").jsonPrimitive.content
            val error = assertFailsWith<Exception>("invalid-decrypt[$index]") {
                decrypt(payload, conversationKey)
            }
            assertTrue(error.message?.contains(note, ignoreCase = true) == true, "expected note '$note', got '${error.message}'")
        }
    }

    @Test
    fun invalidConversationKeys() {
        invalid.getValue("get_conversation_key").jsonArray.forEachIndexed { index, entry ->
            val obj = entry.jsonObject
            val sec1 = obj.getValue("sec1").jsonPrimitive.content.hexToByteArray()
            val pub2 = obj.getValue("pub2").jsonPrimitive.content.hexToByteArray()
            assertFailsWith<Exception>("invalid-conversation-key[$index]") {
                calculateConversationKey(sec1, pub2)
            }
        }
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = Hash.sha256(bytes.asUByteArray())
    return digest.toHexString()
}

private fun assertArraysEqual(expected: UByteArray, actual: UByteArray, label: String) {
    assertTrue(expected.contentEquals(actual), "$label mismatch: expected=${expected.toHexString()} actual=${actual.toHexString()}")
}

private fun readResource(path: String): String {
    val url = Nip44VectorsTest::class.java.classLoader?.getResource(path)
        ?: error("Missing resource: $path")
    return url.readText()
}
