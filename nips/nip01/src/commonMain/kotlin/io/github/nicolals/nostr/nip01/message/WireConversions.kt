package io.github.nicolals.nostr.nip01.message

import io.github.nicolals.nostr.core.primitives.*

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
