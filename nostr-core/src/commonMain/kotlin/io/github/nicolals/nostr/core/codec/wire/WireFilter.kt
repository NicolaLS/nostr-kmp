package io.github.nicolals.nostr.core.codec.wire

data class WireFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Long>? = null,
    val tagFilters: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
)
