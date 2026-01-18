package io.github.nicolals.nostr.core.primitives

/**
 * A Nostr keypair containing both the secret key and its corresponding public key.
 *
 * This is a convenience class that bundles the two keys together. Use factory methods
 * in the crypto module to create instances:
 *
 * ```kotlin
 * // Generate a new random keypair
 * val keys = KeyPair.generate().getOrThrow()
 *
 * // Create from existing secret key
 * val keys = KeyPair.fromSecretKey(secretKey).getOrThrow()
 *
 * // Access keys
 * println("Public key: ${keys.publicKey.hex}")
 * ```
 *
 * @property secretKey The 32-byte secp256k1 secret key
 * @property publicKey The 32-byte x-only public key derived from the secret key
 */
data class KeyPair(
    val secretKey: SecretKey,
    val publicKey: PublicKey,
) {
    companion object
}
