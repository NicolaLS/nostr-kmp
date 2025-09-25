package nostr.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlin.random.Random
import nostr.core.crypto.api.KeyGenerator
import nostr.core.crypto.keys.KeyPair as CoreKeyPair
import nostr.core.crypto.keys.PrivateKey
import nostr.core.crypto.keys.PublicKey

/** Helpers for constructing x-only secp256k1 key pairs. */
object KeyPair {
    fun random(
        backend: Secp256k1 = Secp256k1,
        random: Random = Random.Default,
        entropyFiller: ((ByteArray) -> Unit)? = null
    ): CoreKeyPair {
        val candidate = generateSecret(random, backend, entropyFiller)
        val privateKey = PrivateKey.fromByteArray(candidate)
        val publicKey = derivePublicKey(privateKey, backend)
        return SimpleKeyPair(privateKey, publicKey)
    }

    fun fromPrivateKey(privateKey: PrivateKey, backend: Secp256k1 = Secp256k1): CoreKeyPair {
        require(backend.secKeyVerify(privateKey.toByteArray())) { "Invalid private key." }
        val publicKey = derivePublicKey(privateKey, backend)
        return SimpleKeyPair(privateKey, publicKey)
    }

    fun fromHex(hex: String, backend: Secp256k1 = Secp256k1): CoreKeyPair =
        fromPrivateKey(PrivateKey.fromHex(hex), backend)

    fun fromBytes(bytes: ByteArray, backend: Secp256k1 = Secp256k1): CoreKeyPair =
        fromPrivateKey(PrivateKey.fromByteArray(bytes), backend)

    fun derivePublicKey(privateKey: PrivateKey, backend: Secp256k1 = Secp256k1): PublicKey {
        val full = backend.pubkeyCreate(privateKey.toByteArray())
        val compressed = backend.pubKeyCompress(full)
        val xOnly = compressed.copyOfRange(1, 33)
        return PublicKey.fromByteArray(xOnly)
    }

    fun generator(
        backend: Secp256k1 = Secp256k1,
        random: Random = Random.Default,
        entropyFiller: ((ByteArray) -> Unit)? = null
    ): KeyGenerator = SecpKeyGenerator(random, backend, entropyFiller)

    operator fun invoke(backend: Secp256k1 = Secp256k1): CoreKeyPair = random(backend = backend)

    operator fun invoke(privateKeyHex: String, backend: Secp256k1 = Secp256k1): CoreKeyPair =
        fromHex(privateKeyHex, backend)

    operator fun invoke(bytes: ByteArray, backend: Secp256k1 = Secp256k1): CoreKeyPair =
        fromBytes(bytes, backend)

    private fun generateSecret(
        random: Random,
        backend: Secp256k1,
        entropyFiller: ((ByteArray) -> Unit)?
    ): ByteArray {
        val buffer = ByteArray(32)
        while (true) {
            if (entropyFiller != null) {
                entropyFiller(buffer)
            } else {
                random.nextBytes(buffer)
            }
            if (backend.secKeyVerify(buffer)) {
                return buffer.copyOf()
            }
        }
    }

    private data class SimpleKeyPair(
        override val privateKey: PrivateKey,
        override val publicKey: PublicKey
    ) : CoreKeyPair
}

internal class SecpKeyGenerator(
    private val random: Random = Random.Default,
    private val backend: Secp256k1 = Secp256k1,
    private val entropyFiller: ((ByteArray) -> Unit)? = null
) : KeyGenerator {
    override fun generateKeyPair(): CoreKeyPair = KeyPair.random(backend, random, entropyFiller)

    override fun publicKeyFrom(privateKey: PrivateKey): PublicKey =
        KeyPair.derivePublicKey(privateKey, backend)
}
