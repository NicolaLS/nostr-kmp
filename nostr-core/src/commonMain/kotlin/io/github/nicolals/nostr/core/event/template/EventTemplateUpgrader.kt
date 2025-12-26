package io.github.nicolals.nostr.core.event.template

import io.github.nicolals.nostr.core.event.view.EventView


interface EventTemplateUpgrader<U : EventView> {
    fun upgradeOrNull(draft: EventDraft): EventTemplate<U>?
}
