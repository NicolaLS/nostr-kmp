package io.github.nicolals.nostr.core.message

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds

/**
 * Filters for NIP-01 REQ subscription queries.
 *
 * Tag filters are keyed by the tag name without the leading '#'
 * (for example: tagFilters["e"] = listOf(eventId)).
 */
data class Filter(
    val ids: List<EventId>? = null,
    val authors: List<PublicKey>? = null,
    val kinds: List<EventKind>? = null,
    val tagFilters: Map<String, List<String>>? = null,
    val since: UnixTimeSeconds? = null,
    val until: UnixTimeSeconds? = null,
    val limit: Int? = null,
)
