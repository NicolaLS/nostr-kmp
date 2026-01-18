package io.github.nicolals.nostr.crypto

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventTemplateSigner
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.id.EventIdComputer
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.KeyPair
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SecretKey

/**
 * Built-in implementation of [EventTemplateSigner] using secp256k1 Schnorr signatures.
 *
 * This class provides all the functionality needed to sign Nostr events:
 * - Holds the keypair securely
 * - Signs event templates using BIP-340 Schnorr signatures
 *
 * ## Usage
 *
 * ```kotlin
 * // Generate a new random signer
 * val signer = NostrSigner.generate().getOrThrow()
 *
 * // Create from existing secret key
 * val signer = NostrSigner.fromSecretKey(SecretKey.fromHex("...")).getOrThrow()
 *
 * // Create from existing keypair
 * val signer = NostrSigner(keyPair)
 *
 * // Sign an event template
 * val template = eventTemplate(kind = EventKind(1), ...)
 * val signedEvent = signer.sign(template)
 *
 * // Access keys
 * println("Public key: ${signer.pubkey.hex}")
 * println("Secret key: ${signer.keys.secretKey.hex}")
 * ```
 *
 * @param keys The keypair containing secret and public keys
 */
class NostrSigner(val keys: KeyPair) : EventTemplateSigner {

    override val pubkey: PublicKey get() = keys.publicKey

    override fun <T : EventView> sign(template: EventTemplate<T>): T {
        return template.sign { draft ->
            // Compute event ID (SHA-256 of serialized event)
            val idBytes = EventIdComputer.nip01IdBytes(
                pubkey = pubkey,
                createdAt = draft.createdAt,
                kind = draft.kind,
                tags = draft.tags,
                content = draft.content,
            )

            // Sign the event ID with Schnorr
            val sigResult = NostrSigning.schnorrSign(keys.secretKey.bytes, idBytes)
            val sigBytes = when (sigResult) {
                is CryptoResult.Ok -> sigResult.value
                is CryptoResult.Err -> throw IllegalStateException(
                    "Failed to sign: ${sigResult.error}"
                )
            }

            // Construct the signed event
            Event(
                id = EventId.fromBytes(idBytes),
                pubkey = pubkey,
                createdAt = draft.createdAt,
                kind = draft.kind,
                tags = draft.tags,
                content = draft.content,
                sig = EventSig.fromBytes(sigBytes),
            )
        }
    }

    companion object {
        /**
         * Generates a new [NostrSigner] with a random keypair.
         *
         * @return A [CryptoResult] containing the new signer, or an error if key generation failed
         */
        fun generate(): CryptoResult<NostrSigner> {
            return NostrSigning.generateSecretKey().flatMap { keyBytes ->
                fromSecretKey(SecretKey.fromBytes(keyBytes))
            }
        }

        /**
         * Creates a [NostrSigner] from an existing secret key.
         *
         * Derives the public key from the secret key.
         *
         * @param secretKey The 32-byte secp256k1 secret key
         * @return A [CryptoResult] containing the signer, or an error if the key is invalid
         */
        fun fromSecretKey(secretKey: SecretKey): CryptoResult<NostrSigner> {
            return NostrSigning.derivePublicKeyXOnly(secretKey.bytes).map { pubKeyBytes ->
                val keyPair = KeyPair(
                    secretKey = secretKey,
                    publicKey = PublicKey.fromBytes(pubKeyBytes),
                )
                NostrSigner(keyPair)
            }
        }
    }
}

/**
 * Extension function to create a [KeyPair] from a [SecretKey].
 *
 * @return A [CryptoResult] containing the keypair, or an error if the key is invalid
 */
fun SecretKey.toKeyPair(): CryptoResult<KeyPair> {
    return NostrSigning.derivePublicKeyXOnly(bytes).map { pubKeyBytes ->
        KeyPair(
            secretKey = this,
            publicKey = PublicKey.fromBytes(pubKeyBytes),
        )
    }
}

/**
 * Extension function to generate a random [KeyPair].
 *
 * @return A [CryptoResult] containing the new keypair, or an error if generation failed
 */
fun KeyPair.Companion.generate(): CryptoResult<KeyPair> {
    return NostrSigning.generateSecretKey().flatMap { keyBytes ->
        SecretKey.fromBytes(keyBytes).toKeyPair()
    }
}
