package io.github.nicolals.nostr.core.event.view

import io.github.nicolals.nostr.core.event.Event
import kotlin.jvm.JvmInline

@JvmInline
value class GenericEvent(override val event: Event) : EventView
