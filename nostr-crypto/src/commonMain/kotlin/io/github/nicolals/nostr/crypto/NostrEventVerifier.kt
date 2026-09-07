package io.github.nicolals.nostr.crypto

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.crypto.SigningPrimitives
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.id.EventIdComputer

/** Verifies the canonical event hash before verifying its signature. No results are cached. */
class NostrEventVerifier(
    private val signing: SigningPrimitives = NostrSigning,
) : EventVerifier {
    override fun verify(event: Event): CryptoResult<Unit> {
        val computedId = try {
            EventIdComputer.nip01IdBytes(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        } catch (_: IllegalArgumentException) {
            return CryptoResult.Err(CryptoError.VerificationFailed("Invalid canonical event encoding"))
        }
        if (computedId != event.id.bytes) {
            return CryptoResult.Err(CryptoError.VerificationFailed("Event ID does not match its contents"))
        }
        return signing.schnorrVerify(event.sig.bytes, computedId, event.pubkey.bytes).flatMap { valid ->
            if (valid) CryptoResult.Ok(Unit)
            else CryptoResult.Err(CryptoError.VerificationFailed("Invalid event signature"))
        }
    }
}
