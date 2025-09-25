package nostr.core.crypto.api

import nostr.core.crypto.event.EventId
import nostr.core.crypto.event.Signature
import nostr.core.crypto.keys.PublicKey

/**
 * Verifies Schnorr signatures against x-only public keys. Implementations defer to the
 * chosen crypto backend; `nostr-core` simply needs the boolean result to maintain state
 * (e.g., identity verification).
 */
interface SignatureVerifier {
    /** Returns true when [signature] is valid for the supplied [eventId] and [publicKey]. */
    fun verify(eventId: EventId, signature: Signature, publicKey: PublicKey): Boolean
}
