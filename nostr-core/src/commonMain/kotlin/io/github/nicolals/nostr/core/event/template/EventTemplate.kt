package io.github.nicolals.nostr.core.event.template

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.event.view.GenericEvent
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds

/**
 * An immutable unsigned draft and a cheap factory for its typed signed view.
 *
 * Pass this to EventTemplateSigner.sign. The factory may capture known public fields to avoid
 * parsing them again, but must not cache decrypted content as a substitute for authentication.
 */
class EventTemplate<U : EventView>(
    val draft: EventDraft,
    internal val wrap: (Event) -> U,
)

fun eventTemplate(
    kind: EventKind,
    content: String,
    tags: EventTags = emptyList(),
    createdAt: UnixTimeSeconds = UnixTimeSeconds.now(),
): EventTemplate<GenericEvent> = EventTemplate(
    draft = EventDraft(createdAt, kind, tags, content),
    wrap = ::GenericEvent,
)
