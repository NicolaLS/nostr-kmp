package nostr.core.crypto.keys

/**
 * Convenience interface that binds a `PrivateKey` and its derived `PublicKey`. Backends
 * can expose richer key representations, but the engine only depends on these two values
 * lining up so they can be forwarded to signers and verifiers.
 */
interface KeyPair {
    val privateKey: PrivateKey
    val publicKey: PublicKey
}
