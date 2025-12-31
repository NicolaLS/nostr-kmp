package io.github.nicolals.nostr.nip01.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.EventKind

class MetadataEventUpgrader(
    private val ctx: NipModuleContext,
) : EventUpgrader<MetadataEvent> {
    override val kinds: Set<EventKind> = setOf(MetadataEvent.KIND)

    override fun upgradeOrNull(event: Event): MetadataEvent? =
        MetadataEvent.fromEventOrNull(event, ctx)
}
