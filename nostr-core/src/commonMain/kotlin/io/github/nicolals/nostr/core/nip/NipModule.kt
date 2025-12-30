package io.github.nicolals.nostr.core.nip

import io.github.nicolals.nostr.core.event.template.EventTemplateUpgrader
import io.github.nicolals.nostr.core.event.upgrade.EventUpgradeRegistry
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView

/**
 * Bundle event and template upgraders for a NIP so installation is explicit and consistent.
 */
interface NipModule {
    val eventUpgraders: List<EventUpgrader<out EventView>>
    val templateUpgraders: List<EventTemplateUpgrader<out EventView>>
}

fun EventUpgradeRegistry.Builder.install(nip: NipModule) = apply {
    nip.eventUpgraders.forEach(::register)
}
