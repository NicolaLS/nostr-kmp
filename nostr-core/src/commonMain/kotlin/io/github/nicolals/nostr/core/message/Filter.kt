package io.github.nicolals.nostr.core.message

import io.github.nicolals.nostr.core.codec.JsonValue
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds

/**
 * Filters for NIP-01 REQ subscription queries.
 *
 * Tag filters are keyed by the tag name without the leading '#'
 * (for example: tagFilters["e"] = listOf(eventId)).
 * Optional NIPs can add nonstandard fields through [extraFields]. These fields
 * are preserved by codecs and REQ/COUNT conversions without core registration.
 */
data class Filter(
    val ids: List<EventId>? = null,
    val authors: List<PublicKey>? = null,
    val kinds: List<EventKind>? = null,
    val tagFilters: Map<String, List<String>>? = null,
    val since: UnixTimeSeconds? = null,
    val until: UnixTimeSeconds? = null,
    val limit: Int? = null,
    val extraFields: Map<String, JsonValue> = emptyMap(),
) {
    init {
        requireValidFilterExtensions(extraFields)
    }
}

/** Standard names are reserved so extensions cannot silently replace typed fields. */
fun requireValidFilterExtensions(fields: Map<String, JsonValue>) {
    require(fields.keys.none { isStandardFilterField(it) || it.startsWith('#') }) {
        "Filter extensions must not override standard fields or tag filters"
    }
    require(fields.keys.none { it.isBlank() }) { "Filter extension names must not be blank" }
}

fun isStandardFilterField(name: String): Boolean = when (name) {
    "ids", "authors", "kinds", "since", "until", "limit" -> true
    else -> false
}
