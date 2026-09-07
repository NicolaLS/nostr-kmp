package io.github.nicolals.nostr.core.codec.wire

import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.*

/**
 * Convert a domain Filter to wire format.
 */
fun Filter.toWireFilter(): WireFilter = WireFilter(
    ids = ids?.map { it.hex },
    authors = authors?.map { it.hex },
    kinds = kinds?.map { it.value.toLong() },
    tagFilters = tagFilters,
    since = since?.value,
    until = until?.value,
    limit = limit,
    extraFields = extraFields,
)

/**
 * Convert a wire Filter to domain format.
 * Returns null if any field fails validation.
 */
fun WireFilter.toFilterOrNull(): Filter? = runCatching {
    Filter(
        ids = ids?.map { EventId.fromHex(it) },
        authors = authors?.map { PublicKey.fromHex(it) },
        kinds = kinds?.map {
            require(it in 0L..65535L) { "Event kind out of range" }
            EventKind(it.toInt())
        },
        tagFilters = tagFilters,
        since = since?.let { UnixTimeSeconds(it) },
        until = until?.let { UnixTimeSeconds(it) },
        limit = limit,
        extraFields = extraFields,
    )
}.getOrNull()
