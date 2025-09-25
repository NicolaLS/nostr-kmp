package nostr.core.crypto.api

import nostr.core.crypto.keys.KeyPair
import nostr.core.crypto.keys.PrivateKey
import nostr.core.crypto.keys.PublicKey

/**
 * Optional helper for backends that can create fresh key pairs or derive the x-only
 * public key from a private key. `nostr-core` uses it in the session/identity helpers
 * to avoid duplicating derivation logic.
 */
interface KeyGenerator {
    /** Generates a new private/public key pair using the backend's entropy source. */
    fun generateKeyPair(): KeyPair

    /** Derives the compressed public key corresponding to [privateKey]. */
    fun publicKeyFrom(privateKey: PrivateKey): PublicKey
}
