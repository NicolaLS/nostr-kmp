package nostr.crypto

import fr.acinq.secp256k1.Secp256k1
import nostr.core.crypto.Sha256
import nostr.core.crypto.Signer as CoreSigner
import nostr.core.crypto.api.SignatureVerifier as CoreSignatureVerifier
import nostr.core.crypto.event.EventId
import nostr.core.crypto.event.Signature
import nostr.core.crypto.keys.KeyPair as CoreKeyPair
import nostr.core.crypto.keys.PrivateKey
import nostr.core.identity.Identity as CoreIdentity
import nostr.core.model.Event
import nostr.core.model.EventBuilder
import nostr.core.model.UnsignedEvent
import nostr.core.time.Clock
import nostr.core.time.SystemClock
import kotlin.random.Random

object Identity {
    fun random(
        backend: Secp256k1 = Secp256k1,
        random: Random = Random.Default,
        auxRandProvider: (() -> ByteArray)? = null,
        hasher: Sha256 = Sha256.Default,
        signatureVerifier: CoreSignatureVerifier = SignatureVerifier.default(backend),
        timestampProvider: (() -> Long)? = null
    ): CoreIdentity {
        val keyPair = KeyPair.random(backend, random)
        val signer = Signer.fromKeyPair(keyPair, backend, auxRandProvider)
        return DefaultIdentity(keyPair, signer, signatureVerifier, hasher, timestampClock(timestampProvider))
    }

    fun fromPrivateKeyHex(
        privateKeyHex: String,
        backend: Secp256k1 = Secp256k1,
        auxRandProvider: (() -> ByteArray)? = null,
        hasher: Sha256 = Sha256.Default,
        signatureVerifier: CoreSignatureVerifier = SignatureVerifier.default(backend),
        timestampProvider: (() -> Long)? = null
    ): CoreIdentity =
        fromPrivateKey(PrivateKey.fromHex(privateKeyHex), backend, auxRandProvider, hasher, signatureVerifier, timestampProvider)

    fun fromPrivateKey(
        privateKey: PrivateKey,
        backend: Secp256k1 = Secp256k1,
        auxRandProvider: (() -> ByteArray)? = null,
        hasher: Sha256 = Sha256.Default,
        signatureVerifier: CoreSignatureVerifier = SignatureVerifier.default(backend),
        timestampProvider: (() -> Long)? = null
    ): CoreIdentity {
        val keyPair = KeyPair.fromPrivateKey(privateKey, backend)
        val signer = Signer.fromKeyPair(keyPair, backend, auxRandProvider)
        return DefaultIdentity(keyPair, signer, signatureVerifier, hasher, timestampClock(timestampProvider))
    }

    fun fromKeyPair(
        keyPair: CoreKeyPair,
        backend: Secp256k1 = Secp256k1,
        auxRandProvider: (() -> ByteArray)? = null,
        hasher: Sha256 = Sha256.Default,
        signatureVerifier: CoreSignatureVerifier = SignatureVerifier.default(backend),
        timestampProvider: (() -> Long)? = null
    ): CoreIdentity {
        val signer = Signer.fromKeyPair(keyPair, backend, auxRandProvider)
        return DefaultIdentity(keyPair, signer, signatureVerifier, hasher, timestampClock(timestampProvider))
    }

    fun fromSigner(
        signer: CoreSigner,
        backend: Secp256k1 = Secp256k1,
        hasher: Sha256 = Sha256.Default,
        signatureVerifier: CoreSignatureVerifier = SignatureVerifier.default(backend),
        timestampProvider: (() -> Long)? = null
    ): CoreIdentity = DefaultIdentity(null, signer, signatureVerifier, hasher, timestampClock(timestampProvider))

    private fun timestampClock(provider: (() -> Long)?): Clock =
        provider?.let { timestampProvider -> object : Clock { override fun nowSeconds(): Long = timestampProvider() } }
            ?: SystemClock
}

private class DefaultIdentity(
    override val keyPair: CoreKeyPair?,
    private val signer: CoreSigner,
    private val signatureVerifier: CoreSignatureVerifier,
    private val hasher: Sha256,
    private val clock: Clock
) : CoreIdentity, CoreSigner by signer {

    private val signerPublicKeyHex = signer.publicKey.toString()

    init {
        keyPair?.let {
            require(it.publicKey == signer.publicKey) { "Signer public key must match key pair public key." }
        }
    }

    override fun newEventBuilder(protectSigner: Boolean): EventBuilder =
        EventBuilder(clock = clock, hasher = hasher, protectSigner = protectSigner).signer(this)

    override fun sign(unsignedEvent: UnsignedEvent): Event = unsignedEvent.sign(this, hasher)

    override fun verify(event: Event): Boolean {
        if (event.pubkey != signerPublicKeyHex) return false
        if (!event.verifyId(hasher)) return false
        val eventId = EventId.fromHex(event.id)
        val signature = Signature.fromHex(event.sig)
        return signatureVerifier.verify(eventId, signature, signer.publicKey)
    }
}
