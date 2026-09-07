package io.github.nicolals.nostr.nip45

import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.nip45.message.Nip45ClientMessageUpgrader
import io.github.nicolals.nostr.nip45.message.Nip45RelayMessageUpgrader

/**
 * NIP-45 module providing:
 * - Protocol message views for client COUNT messages
 * - Protocol message views for relay COUNT messages
 *
 * This enables clients to request event counts from relays that support
 * the COUNT verb, which is useful for obtaining counts without fetching
 * all matching events (e.g., follower counts, post counts).
 */
class Nip45Module(
    codec: NostrWireCodec,
) : NipModule {
    override val clientMessageUpgraders: List<ClientMessageUpgrader<*>> =
        listOf(Nip45ClientMessageUpgrader(codec))

    override val relayMessageUpgraders: List<RelayMessageUpgrader<*>> =
        listOf(Nip45RelayMessageUpgrader())
}
