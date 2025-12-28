package io.github.nicolals.nostr.core.event.template

import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds

data class EventDraft(
    val createdAt: UnixTimeSeconds,
    val kind: EventKind,
    val tags: EventTags,
    val content: String,
)
