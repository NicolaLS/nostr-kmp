package io.github.nicolals.nostr.nip04.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.EventKind

class EncryptedDirectMessageEventUpgrader(
    private val ctx: NipModuleContext,
) : EventUpgrader<EncryptedDirectMessageEvent> {
    override val kinds: Set<EventKind> = setOf(EncryptedDirectMessageEvent.KIND)

    override fun upgradeOrNull(event: Event): EncryptedDirectMessageEvent? =
        EncryptedDirectMessageEvent.fromEventOrNull(event, ctx)
}
