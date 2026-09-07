package io.github.nicolals.nostr.core.primitives

typealias EventTag = List<String>
typealias EventTags = List<EventTag>

/** Events and drafts own immutable snapshots of both list levels. */
internal fun snapshotEventTags(tags: EventTags): EventTags =
    if (tags is ImmutableEventTags) tags else ImmutableEventTags(tags)

private class ImmutableEventTags(tags: EventTags) : AbstractList<EventTag>() {
    private val entries = tags.map { ImmutableEventTag(it) }
    override val size: Int get() = entries.size
    override fun get(index: Int): EventTag = entries[index]
}

private class ImmutableEventTag(tag: EventTag) : AbstractList<String>() {
    private val entries = tag.toList()
    override val size: Int get() = entries.size
    override fun get(index: Int): String = entries[index]
}
