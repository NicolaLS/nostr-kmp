package nostr.crypto

import fr.acinq.secp256k1.Secp256k1
import nostr.core.crypto.api.SignatureVerifier as CoreSignatureVerifier
import nostr.core.crypto.event.EventId
import nostr.core.crypto.event.Signature
import nostr.core.crypto.keys.PublicKey

object SignatureVerifier {
    fun default(backend: Secp256k1 = Secp256k1): CoreSignatureVerifier = SecpSignatureVerifier(backend)
}

/** Default secp256k1 verifier that delegates to ACINQ. */
internal class SecpSignatureVerifier(
    private val backend: Secp256k1 = Secp256k1
) : CoreSignatureVerifier {
    override fun verify(eventId: EventId, signature: Signature, publicKey: PublicKey): Boolean =
        backend.verifySchnorr(signature.toByteArray(), eventId.toByteArray(), publicKey.toByteArray())
}
