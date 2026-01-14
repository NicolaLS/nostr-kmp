package io.github.nicolals.nostr.nip01.message

import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.*

/**
 * Convert a domain Event to wire format.
 */
internal fun Event.toWireEvent(): WireEvent = WireEvent(
    id = id.hex,
    pubkey = pubkey.hex,
    createdAt = createdAt.value,
    kind = kind.value,
    tags = tags,
    content = content,
    sig = sig.hex,
)

/**
 * Convert a wire Event to domain format.
 * Returns null if any field fails validation.
 */
internal fun WireEvent.toEventOrNull(): Event? = runCatching {
    Event(
        id = EventId.fromHex(id),
        pubkey = PublicKey.fromHex(pubkey),
        createdAt = UnixTimeSeconds(createdAt),
        kind = EventKind(kind),
        tags = tags,
        content = content,
        sig = EventSig.fromHex(sig),
    )
}.getOrNull()

/**
 * Convert a domain Filter to wire format.
 */
internal fun Filter.toWireFilter(): WireFilter = WireFilter(
    ids = ids?.map { it.hex },
    authors = authors?.map { it.hex },
    kinds = kinds?.map { it.value.toLong() },
    tagFilters = tagFilters,
    since = since?.value,
    until = until?.value,
    limit = limit,
)

/**
 * Convert a wire Filter to domain format.
 * Returns null if any field fails validation.
 */
internal fun WireFilter.toFilterOrNull(): Filter? = runCatching {
    Filter(
        ids = ids?.map { EventId.fromHex(it) },
        authors = authors?.map { PublicKey.fromHex(it) },
        kinds = kinds?.map { EventKind(it.toInt()) },
        tagFilters = tagFilters,
        since = since?.let { UnixTimeSeconds(it) },
        until = until?.let { UnixTimeSeconds(it) },
        limit = limit,
    )
}.getOrNull()

/**
 * Convert a string to a SubscriptionId.
 * Returns null if validation fails.
 */
internal fun String.toSubscriptionIdOrNull(): SubscriptionId? = runCatching {
    SubscriptionId(this)
}.getOrNull()

/**
 * Convert a hex string to an EventId.
 * Returns null if validation fails.
 */
internal fun String.toEventIdOrNull(): EventId? = runCatching {
    EventId.fromHex(this)
}.getOrNull()
