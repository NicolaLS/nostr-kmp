import fr.acinq.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Nip04Test {

    private val senderPrivHex = "0000000000000000000000000000000000000000000000000000000000000001"
    private val recipientPrivHex = "0000000000000000000000000000000000000000000000000000000000000002"
    private val message = "hello nip04"
    private val vectorSharedSecret = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
    private val vectorCiphertext = "M1IS4Gj6Dt3tXh6Eo2RrmA==?iv=AAAAAAAAAAAAAAAAAAAAAA=="

    @Test
    fun deriveSharedSecretMatchesVector() {
        val senderPriv = senderPrivHex.hexToByteArray()
        val recipientPub = deriveCompressed(recipientPrivHex.hexToByteArray())
        val shared = deriveSharedSecret(senderPriv, recipientPub)
        assertEquals(vectorSharedSecret, shared.toHexString())
    }

    @Test
    fun deriveSharedSecretAcceptsXOnlyPublicKey() {
        val senderPriv = senderPrivHex.hexToByteArray()
        val compressed = deriveCompressed(recipientPrivHex.hexToByteArray())
        val xOnly = compressed.copyOfRange(1, 33)
        val shared = deriveSharedSecret(senderPriv, xOnly)
        assertEquals(vectorSharedSecret, shared.toHexString())
    }

    @Test
    fun deriveSharedSecretAcceptsUncompressedPublicKey() {
        val senderPriv = senderPrivHex.hexToByteArray()
        val uncompressed = deriveUncompressed(recipientPrivHex.hexToByteArray())
        val shared = deriveSharedSecret(senderPriv, uncompressed)
        assertEquals(vectorSharedSecret, shared.toHexString())
    }

    @Test
    fun encryptProducesKnownVector() {
        val shared = vectorSharedSecret.hexToByteArray()
        val iv = ByteArray(16) // zero IV for deterministic vector
        val ciphertext = encryptWithSharedSecret(message, shared, iv)
        assertEquals(vectorCiphertext, ciphertext)
    }

    @Test
    fun decryptRestoresPlaintext() {
        val shared = vectorSharedSecret.hexToByteArray()
        val plaintext = decryptWithSharedSecret(vectorCiphertext, shared)
        assertEquals(message, plaintext)
    }

    @Test
    fun encryptDecryptWithKeysRoundTrip() {
        val recipientPub = deriveCompressed(recipientPrivHex.hexToByteArray())
        val ciphertext = encrypt(message, senderPrivHex, recipientPub.toHexString(), iv = ByteArray(16))
        val decrypted = decrypt(ciphertext, recipientPrivHex, deriveCompressed(senderPrivHex.hexToByteArray()).toHexString())
        assertEquals(message, decrypted)
    }

    @Test
    fun decryptFailsOnMalformedPayload() {
        val shared = vectorSharedSecret.hexToByteArray()
        assertFailsWith<IllegalArgumentException> {
            decryptWithSharedSecret("invalid-payload", shared)
        }
        assertFailsWith<IllegalArgumentException> {
            decryptWithSharedSecret("abc?iv=bad!!!", shared)
        }
        assertFailsWith<IllegalArgumentException> {
            decryptWithSharedSecret("YWJj?iv=YWJj", shared) // ciphertext not multiple of block size
        }
    }

    @Test
    fun encryptValidatesKeyLengths() {
        val recipientPub = deriveCompressed(recipientPrivHex.hexToByteArray()).toHexString()
        assertFailsWith<IllegalArgumentException> {
            encrypt(message, "1234", recipientPub)
        }
        assertFailsWith<IllegalArgumentException> {
            encrypt(message, senderPrivHex, "abcd")
        }
    }

    private fun deriveCompressed(secretKey: ByteArray): ByteArray {
        val full = Secp256k1.pubkeyCreate(secretKey)
        return Secp256k1.pubKeyCompress(full)
    }

    private fun deriveUncompressed(secretKey: ByteArray): ByteArray = Secp256k1.pubkeyCreate(secretKey)
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    val result = ByteArray(length / 2)
    var index = 0
    while (index < length) {
        val byte = substring(index, index + 2).toInt(16)
        result[index / 2] = byte.toByte()
        index += 2
    }
    return result
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
