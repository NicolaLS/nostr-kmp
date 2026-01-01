package io.github.nicolals.nostr.nip47.model

import io.github.nicolals.nostr.core.codec.JsonObject

data class NwcTransaction(
    val type: String? = null,
    val state: String? = null,
    val invoice: String? = null,
    val description: String? = null,
    val descriptionHash: String? = null,
    val preimage: String? = null,
    val paymentHash: String? = null,
    val amountMsat: Long? = null,
    val feesPaidMsat: Long? = null,
    val createdAt: Long? = null,
    val expiresAt: Long? = null,
    val settledAt: Long? = null,
    val metadata: JsonObject? = null,
)
