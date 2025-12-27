package io.github.nicolals.nostr.core.message

import io.github.nicolals.nostr.core.crypto.PublicKey
import io.github.nicolals.nostr.core.event.EventKind
import io.github.nicolals.nostr.core.types.HexKey

/**
 * Filters for NIP-01 REQ subscription queries.
 *
 * Tag filters are keyed by the tag name without the leading '#'
 * (for example: tagFilters["e"] = listOf(eventId)).
 */
data class Filter(
    val ids: List<HexKey>? = null,
    val authors: List<PublicKey>? = null,
    val kinds: List<EventKind>? = null,
    val tagFilters: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
)
