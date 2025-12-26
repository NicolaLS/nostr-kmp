package io.github.nicolals.nostr.core.event.upgrade

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.EventKind
import io.github.nicolals.nostr.core.event.view.EventView

interface EventUpgrader<U : EventView> {
    /** For fast routing. Can be a single kind or a small set. */
    val kinds: Set<EventKind>

    /** Higher wins when multiple upgraders match the same event. */
    val priority: Int get() = 0

    /**
     * Return null if this event is not a valid U.
     * This is where you can check tags, attempt decrypt, parse JSON, etc.
     */
    fun upgradeOrNull(event: Event): U?
}