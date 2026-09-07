package io.github.nicolals.nostr.core.crypto

import io.github.nicolals.nostr.core.event.Event

/** Verifies both the canonical NIP-01 event ID and its BIP-340 signature. */
fun interface EventVerifier {
    fun verify(event: Event): CryptoResult<Unit>
}
