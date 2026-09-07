package io.github.nicolals.nostr.nip04

import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.nip04.event.EncryptedDirectMessageEventUpgrader

/** Registers the kind-4 event view; encryption capabilities are configured separately. */
class Nip04Module : NipModule {
    override val eventUpgraders: List<EventUpgrader<out EventView>> =
        listOf(EncryptedDirectMessageEventUpgrader())
}
