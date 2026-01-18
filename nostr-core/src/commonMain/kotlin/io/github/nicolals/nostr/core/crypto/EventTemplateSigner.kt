package io.github.nicolals.nostr.core.crypto

import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.primitives.PublicKey

/**
 * Signs event templates to produce signed Nostr events.
 *
 * This interface abstracts the signing operation, allowing for different implementations:
 * - In-memory private key signing ([NostrSigner])
 * - Hardware security module signing
 * - Remote signing (NIP-46 bunker)
 * - Browser extension signing (NIP-07)
 *
 * ## Usage
 *
 * ```kotlin
 * val signer: EventTemplateSigner = NostrSigner.generate().getOrThrow()
 *
 * val template = eventTemplate(
 *     kind = EventKind(1),
 *     createdAt = UnixTimeSeconds.now(),
 *     tags = emptyList(),
 *     content = "Hello, Nostr!"
 * )
 *
 * val signedEvent = signer.sign(template)
 * ```
 */
interface EventTemplateSigner {
    /**
     * The public key associated with this signer.
     */
    val pubkey: PublicKey

    /**
     * Signs an event template and returns the typed event view.
     *
     * The implementation computes the event ID (SHA-256 of serialized event data),
     * creates a Schnorr signature, and constructs the complete signed event.
     *
     * @param template The unsigned event template to sign
     * @return The signed event wrapped in its typed view
     */
    fun <T : EventView> sign(template: EventTemplate<T>): T
}
