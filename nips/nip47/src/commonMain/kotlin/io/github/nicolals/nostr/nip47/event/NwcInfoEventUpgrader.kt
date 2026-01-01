package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.primitives.EventKind

class NwcInfoEventUpgrader : EventUpgrader<NwcInfoEvent> {
    override val kinds: Set<EventKind> = setOf(NwcInfoEvent.KIND)

    override fun upgradeOrNull(event: Event): NwcInfoEvent? =
        NwcInfoEvent.fromEventOrNull(event)
}
