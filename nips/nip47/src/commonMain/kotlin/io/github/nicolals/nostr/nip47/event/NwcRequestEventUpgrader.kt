package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector

class NwcRequestEventUpgrader(
    private val encryptionSelector: NwcEncryptionSelector,
) : EventUpgrader<NwcRequestEvent> {
    override val kinds: Set<EventKind> = setOf(NwcRequestEvent.KIND)

    override fun upgradeOrNull(event: Event): NwcRequestEvent? =
        NwcRequestEvent.fromEventOrNull(event, encryptionSelector)
}
