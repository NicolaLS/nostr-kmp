package nostr.core.crypto

import nostr.core.crypto.event.EventId
import nostr.core.crypto.event.Signature
import nostr.core.crypto.keys.PublicKey

/**
 * Signs Nostr event identifiers using Schnorr/secp256k1.
 *
 * Implementations are expected to manage private-key material and expose the
 * corresponding compressed public key via [publicKey]. `nostr-core` only cares
 * about the deterministic signature result; key storage, hardware integration,
 * or precomputation are handled by the implementation.
 */
interface Signer {
    /** The x-only public key that will verify signatures produced by this signer. */
    val publicKey: PublicKey

    /** Produces a Schnorr signature for the supplied, canonical [eventId]. */
    fun sign(eventId: EventId): Signature

    /** Convenience accessor that returns the compressed public key bytes. */
    fun pubkey(): ByteArray = publicKey.toByteArray()

    /**
     * Signs a raw 32-byte hash. This exists for integrations that already hashed the
     * canonical array and want the signature in byte form without converting through
     * [EventId] and [Signature]. The hash MUST be the canonical SHA-256 of the event array.
     */
    fun signRaw32(eventHash32: ByteArray): ByteArray {
        require(eventHash32.size == 32) { "Hash must be 32 bytes." }
        return sign(EventId.fromByteArray(eventHash32)).toByteArray()
    }
}
