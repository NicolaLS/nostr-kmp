package io.github.nicolals.nostr.core.primitives

import kotlin.jvm.JvmInline

@JvmInline
value class SubscriptionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Subscription id must not be blank" }
        require(value.length <= 64) { "Subscription id can not be longer than 64 characters" }
    }
}
