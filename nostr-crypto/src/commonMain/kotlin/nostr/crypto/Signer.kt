package nostr.crypto

import fr.acinq.secp256k1.Secp256k1
import nostr.core.crypto.Signer as CoreSigner
import nostr.core.crypto.event.EventId
import nostr.core.crypto.event.Signature
import nostr.core.crypto.keys.KeyPair as CoreKeyPair
import nostr.core.crypto.keys.PrivateKey
import nostr.core.crypto.keys.PublicKey

/** Helpers for producing Schnorr signers backed by ACINQ's secp256k1 implementation. */
object Signer {
    fun fromPrivateKey(
        privateKey: PrivateKey,
        backend: Secp256k1 = Secp256k1,
        auxRandProvider: (() -> ByteArray)? = null
    ): CoreSigner {
        val publicKey = KeyPair.derivePublicKey(privateKey, backend)
        return Secp256k1Signer(privateKey, publicKey, backend, auxRandProvider)
    }

    fun fromPrivateKeyHex(
        privateKeyHex: String,
        backend: Secp256k1 = Secp256k1,
        auxRandProvider: (() -> ByteArray)? = null
    ): CoreSigner = fromPrivateKey(PrivateKey.fromHex(privateKeyHex), backend, auxRandProvider)

    fun fromKeyPair(
        keyPair: CoreKeyPair,
        backend: Secp256k1 = Secp256k1,
        auxRandProvider: (() -> ByteArray)? = null
    ): CoreSigner {
        val expected = KeyPair.derivePublicKey(keyPair.privateKey, backend)
        require(expected == keyPair.publicKey) { "Public key does not match derived private key." }
        return Secp256k1Signer(keyPair.privateKey, keyPair.publicKey, backend, auxRandProvider)
    }
}

internal class Secp256k1Signer(
    private val privateKey: PrivateKey,
    override val publicKey: PublicKey,
    private val backend: Secp256k1 = Secp256k1,
    private val auxRandProvider: (() -> ByteArray)? = null
) : CoreSigner {

    init {
        require(backend.secKeyVerify(privateKey.toByteArray())) { "Invalid private key supplied." }
    }

    override fun sign(eventId: EventId): Signature {
        val aux = auxRandProvider?.invoke()?.also {
            require(it.size == 32) { "Auxiliary randomness must be 32 bytes." }
        }
        val signatureBytes = backend.signSchnorr(eventId.toByteArray(), privateKey.toByteArray(), aux)
        return Signature.fromByteArray(signatureBytes)
    }
}
