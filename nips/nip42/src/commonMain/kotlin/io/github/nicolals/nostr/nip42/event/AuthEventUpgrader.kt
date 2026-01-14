package io.github.nicolals.nostr.nip42.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.primitives.EventKind

/**
 * Upgrader for NIP-42 authentication events (kind 22242).
 */
class AuthEventUpgrader : EventUpgrader<AuthEvent> {

    override val kinds: Set<EventKind> = setOf(AuthEvent.KIND)

    override fun upgradeOrNull(event: Event): AuthEvent? {
        return AuthEvent.fromEventOrNull(event)
    }
}
