package io.github.nicolals.nostr.core.event

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.primitives.snapshotEventTags

/**
 * A NIP-01 event with immutable fields, including a snapshot of both tag list levels.
 *
 * Construction alone does not verify the ID or signature; use an EventVerifier for untrusted events.
 */
class Event(
    val id: EventId,
    val pubkey: PublicKey,
    val createdAt: UnixTimeSeconds,
    val kind: EventKind,
    tags: EventTags,
    val content: String,
    val sig: EventSig,
) {
    val tags: EventTags = snapshotEventTags(tags)

    fun copy(
        id: EventId = this.id,
        pubkey: PublicKey = this.pubkey,
        createdAt: UnixTimeSeconds = this.createdAt,
        kind: EventKind = this.kind,
        tags: EventTags = this.tags,
        content: String = this.content,
        sig: EventSig = this.sig,
    ): Event = Event(id, pubkey, createdAt, kind, tags, content, sig)

    override fun equals(other: Any?): Boolean = other is Event &&
        id == other.id && pubkey == other.pubkey && createdAt == other.createdAt &&
        kind == other.kind && tags == other.tags && content == other.content && sig == other.sig

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + pubkey.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + content.hashCode()
        return 31 * result + sig.hashCode()
    }

    override fun toString(): String =
        "Event(id=$id, pubkey=$pubkey, createdAt=$createdAt, kind=$kind, tags=$tags, content=$content, sig=$sig)"
}
