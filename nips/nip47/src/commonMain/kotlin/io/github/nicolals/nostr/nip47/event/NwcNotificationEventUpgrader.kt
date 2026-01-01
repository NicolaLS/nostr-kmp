package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.primitives.EventKind
class NwcNotificationEventUpgrader : EventUpgrader<NwcNotificationEvent> {
    override val kinds: Set<EventKind> = setOf(
        NwcNotificationEvent.KIND,
        NwcNotificationEvent.LEGACY_KIND,
    )

    override fun upgradeOrNull(event: Event): NwcNotificationEvent? =
        NwcNotificationEvent.fromEventOrNull(event)
}
