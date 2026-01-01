package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector

class NwcResponseEventUpgrader(
    private val encryptionSelector: NwcEncryptionSelector,
) : EventUpgrader<NwcResponseEvent> {
    override val kinds: Set<EventKind> = setOf(NwcResponseEvent.KIND)

    override fun upgradeOrNull(event: Event): NwcResponseEvent? =
        NwcResponseEvent.fromEventOrNull(event, encryptionSelector)
}
