package nostr.core.identity

import nostr.core.crypto.Signer
import nostr.core.crypto.keys.KeyPair
import nostr.core.model.Event
import nostr.core.model.EventBuilder
import nostr.core.model.UnsignedEvent

/**
 * Minimal identity contract exposed by `nostr-core`. Implementations are expected to provide
 * signing, verification, and builder ergonomics atop a specific crypto backend. See the
 * `nostr-crypto` module for the default secp256k1 implementation.
 */
interface Identity : Signer {
    /** Optional key pair; implementations may keep the private key internal. */
    val keyPair: KeyPair?

    /** Returns an [EventBuilder] primed with this identity's signing context. */
    fun newEventBuilder(protectSigner: Boolean = true): EventBuilder

    /** Signs an [UnsignedEvent]. */
    fun sign(unsignedEvent: UnsignedEvent): Event

    /** Verifies that a signed [event] matches the identity's public key. */
    fun verify(event: Event): Boolean
}
