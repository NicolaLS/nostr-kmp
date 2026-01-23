package io.github.nicolals.nostr.bench

import kotlinx.serialization.Serializable

@Serializable
data class NwcMetrics(
    val totalRequests: Long,
    val sentRequests: Long,
    val responses: Long,
    val responsesOk: Long,
    val responsesError: Long,
    val responsesInvalid: Long,
    val responsesUnmatched: Long,
    val responsesDuplicate: Long,
    val timeouts: Long,
    val minLatencyNs: Long? = null,
    val maxLatencyNs: Long? = null,
    val avgLatencyNs: Double? = null,
    val encryption: NwcEncryptionMetrics = NwcEncryptionMetrics(),
)

@Serializable
data class NwcEncryptionMetrics(
    val nip04Requests: Long = 0,
    val nip44Requests: Long = 0,
    val nip04Responses: Long = 0,
    val nip44Responses: Long = 0,
)
