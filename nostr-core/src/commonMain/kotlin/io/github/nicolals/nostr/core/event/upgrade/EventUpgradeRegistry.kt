package io.github.nicolals.nostr.core.event.upgrade

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.event.view.GenericEvent
import io.github.nicolals.nostr.core.primitives.EventKind

class EventUpgradeRegistry private constructor(
    private val byKind: Map<EventKind, List<EventUpgrader<out EventView>>>,
) {
    class Builder {
        private val list = mutableListOf<EventUpgrader<out EventView>>()

        fun register(u: EventUpgrader<out EventView>) = apply { list += u }

        fun build(): EventUpgradeRegistry {
            val map = mutableMapOf<EventKind, MutableList<EventUpgrader<out EventView>>>()
            for (u in list) {
                for (k in u.kinds) map.getOrPut(k) { mutableListOf() }.add(u)
            }
            map.values.forEach { it.sortByDescending { u -> u.priority } }
            return EventUpgradeRegistry(map)
        }
    }

    fun upgrade(event: Event): EventView {
        val candidates = byKind[event.kind].orEmpty()
        for (u in candidates) {
            val v = u.upgradeOrNull(event)
            if (v != null) return v
        }
        return GenericEvent(event)
    }
}