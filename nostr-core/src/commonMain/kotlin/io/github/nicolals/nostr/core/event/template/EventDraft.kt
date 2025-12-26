package io.github.nicolals.nostr.core.event.template

import io.github.nicolals.nostr.core.event.EventKind
import io.github.nicolals.nostr.core.event.EventTags

data class EventDraft(
    val createdAt: Long,
    val kind: EventKind,
    val tags: EventTags,
    val content: String,
)
