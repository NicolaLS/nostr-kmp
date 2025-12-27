package io.github.nicolals.nostr.core.event.template

import io.github.nicolals.nostr.core.event.EventKind
import io.github.nicolals.nostr.core.event.EventTags
import io.github.nicolals.nostr.core.types.UnixTimeSeconds

data class EventDraft(
    val createdAt: UnixTimeSeconds,
    val kind: EventKind,
    val tags: EventTags,
    val content: String,
)
