package io.github.nicolals.nostr.nip45.message

import io.github.nicolals.nostr.core.primitives.*

/**
 * Convert a string to a SubscriptionId.
 * Returns null if validation fails.
 */
internal fun String.toSubscriptionIdOrNull(): SubscriptionId? = runCatching {
    SubscriptionId(this)
}.getOrNull()
