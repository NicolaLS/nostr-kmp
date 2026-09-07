package io.github.nicolals.nostr.core.event.template

import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.primitives.snapshotEventTags

/** Immutable unsigned event data, safe to retain while an asynchronous signer is pending. */
class EventDraft(
    val createdAt: UnixTimeSeconds,
    val kind: EventKind,
    tags: EventTags,
    val content: String,
) {
    val tags: EventTags = snapshotEventTags(tags)

    fun copy(
        createdAt: UnixTimeSeconds = this.createdAt,
        kind: EventKind = this.kind,
        tags: EventTags = this.tags,
        content: String = this.content,
    ): EventDraft = EventDraft(createdAt, kind, tags, content)

    override fun equals(other: Any?): Boolean = other is EventDraft &&
        createdAt == other.createdAt && kind == other.kind && tags == other.tags && content == other.content

    override fun hashCode(): Int {
        var result = createdAt.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + tags.hashCode()
        return 31 * result + content.hashCode()
    }

    override fun toString(): String =
        "EventDraft(createdAt=$createdAt, kind=$kind, tags=$tags, content=$content)"
}
