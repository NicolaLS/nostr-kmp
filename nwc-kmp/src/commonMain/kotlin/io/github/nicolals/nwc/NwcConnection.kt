package io.github.nicolals.nwc

import io.github.nicolals.nostr.core.primitives.PublicKey

/** Connection routing and public identities, without a client private key. */
class NwcConnection(
    val walletPubkey: PublicKey,
    val clientPubkey: PublicKey,
    relays: List<String>,
) {
    val relays: List<String> = relays.toList()

    init {
        require(this.relays.isNotEmpty()) { "At least one NWC relay is required" }
    }

    override fun toString(): String = "NwcConnection(walletPubkey=$walletPubkey, clientPubkey=$clientPubkey, relays=[redacted])"
}
