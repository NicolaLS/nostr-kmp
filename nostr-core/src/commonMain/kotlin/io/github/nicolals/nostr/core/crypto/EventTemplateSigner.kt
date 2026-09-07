package io.github.nicolals.nostr.core.crypto

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.primitives.PublicKey

/**
 * An account's event-signing capability. Implementations may use local keys, a remote signer,
 * a browser extension, or hardware without exposing private key material.
 *
 * Signing can suspend for I/O or user approval. Cancellation propagates to the caller;
 * signing failures and rejected requests are returned as [CryptoResult.Err].
 */
interface EventTemplateSigner {
    val pubkey: PublicKey

    suspend fun sign(draft: EventDraft): CryptoResult<Event>

    /** Signs the immutable draft and wraps the matching result in its typed event view. */
    suspend fun <T : EventView> sign(template: EventTemplate<T>): CryptoResult<T> =
        sign(template.draft).flatMap { event ->
            val draft = template.draft
            if (event.pubkey != pubkey || event.createdAt != draft.createdAt || event.kind != draft.kind ||
                event.tags != draft.tags || event.content != draft.content
            ) {
                CryptoResult.Err(CryptoError.SigningFailed("Signer returned an event for a different draft"))
            } else {
                CryptoResult.Ok(template.wrap(event))
            }
        }
}
