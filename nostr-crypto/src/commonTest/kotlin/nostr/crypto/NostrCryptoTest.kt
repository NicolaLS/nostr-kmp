package nostr.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nostr.core.Nip01Canonical
import nostr.core.crypto.Sha256
import nostr.core.crypto.event.EventId
import nostr.core.time.SystemClock
import nostr.core.utils.hexToByteArray
import nostr.core.utils.toHexLower

class NostrCryptoTest {

    @Test
    fun identityBuildsSignedEvent() {
        val privateKeyHex = "0000000000000000000000000000000000000000000000000000000000000003"
        val expectedPublicKey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val timestamp = 1_700_000_000L

        val identity = Identity.fromPrivateKeyHex(
            privateKeyHex,
            timestampProvider = { timestamp }
        )

        assertEquals(expectedPublicKey, identity.publicKey.toString())

        val event = identity
            .newEventBuilder()
            .kind(1)
            .content("hello nostr")
            .addTag("p", expectedPublicKey)
            .build()

        assertEquals(expectedPublicKey, event.pubkey)
        assertEquals(timestamp, event.createdAt)

        val serialized = Nip01Canonical.serializeEventArrayForSigning(event.canonicalFields())
        val hash = Sha256.Default.hash(serialized)
        assertContentEquals(hash, event.id.hexToByteArray())

        val backend = Secp256k1
        assertTrue(
            backend.verifySchnorr(
                event.sig.hexToByteArray(),
                event.id.hexToByteArray(),
                event.pubkey.hexToByteArray()
            )
        )
        assertTrue(identity.verify(event))
    }

    @Test
    fun keyGeneratorSkipsInvalidCandidates() {
        val validKey = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToByteArray()
        val fillerInputs = arrayOf(ByteArray(32) { 0 }, validKey)
        var index = 0
        val generator = KeyPair.generator(
            entropyFiller = { bytes ->
                fillerInputs[index].copyInto(bytes)
                index += 1
            }
        )

        val keyPair = generator.generateKeyPair()
        assertEquals(validKey.toHexLower(), keyPair.privateKey.toByteArray().toHexLower())
        val backend = Secp256k1
        assertTrue(backend.secKeyVerify(keyPair.privateKey.toByteArray()))
        assertTrue(index >= 2)
    }

    @Test
    fun signerFromHexMatchesSignerFromKeyPair() {
        val privateKeyHex = "01".repeat(32)
        val keyPair = KeyPair.fromHex(privateKeyHex)
        val signerFromKeyPair = Signer.fromKeyPair(keyPair)
        val signerFromHex = Signer.fromPrivateKeyHex(privateKeyHex)

        assertEquals(signerFromKeyPair.publicKey, signerFromHex.publicKey)

        val eventId = EventId.fromByteArray(ByteArray(32) { index -> index.toByte() })
        assertEquals(
            signerFromKeyPair.sign(eventId),
            signerFromHex.sign(eventId)
        )
    }

    @Test
    fun identityUsesSystemClockWhenTimestampNotProvided() {
        val identity = Identity.random(random = Random.Default)
        val start = SystemClock.nowSeconds()

        val event = identity
            .newEventBuilder()
            .kind(1)
            .content("default clock")
            .build()

        val end = SystemClock.nowSeconds()

        assertTrue(event.createdAt >= start)
        assertTrue(event.createdAt <= end)
    }
}
