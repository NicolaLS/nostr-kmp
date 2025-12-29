package io.github.nicolals.nostr.core.codec.wire

typealias WireEventTag = List<String>
typealias WireEventTags = List<WireEventTag>

data class WireEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: WireEventTags,
    val content: String,
    val sig: String,
)
