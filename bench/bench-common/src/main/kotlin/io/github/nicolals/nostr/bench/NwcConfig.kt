package io.github.nicolals.nostr.bench

import kotlinx.serialization.Serializable

@Serializable
data class NwcScenario(
    val requestCount: Int = 100,
    val ratePerSec: Int? = null,
    val timeoutMs: Long = 5_000,
    val clientSecretKeyHex: String? = null,
    val walletSecretKeyHex: String? = null,
    val encryption: NwcEncryptionPlan = NwcEncryptionPlan(),
    val request: NwcRequestPlan = NwcRequestPlan(),
    val wallet: NwcWalletBehavior = NwcWalletBehavior(),
)

@Serializable
data class NwcEncryptionPlan(
    val nip04Ratio: Double = 0.0,
)

@Serializable
data class NwcRequestPlan(
    val invoiceSize: Int = 180,
    val amountMsat: Long? = 1_000,
)

@Serializable
data class NwcWalletBehavior(
    val minDelayMs: Long = 0,
    val maxDelayMs: Long = 0,
    val errorRate: Double = 0.0,
    val dropRate: Double = 0.0,
    val duplicateRate: Double = 0.0,
    val wrongTagRate: Double = 0.0,
    val invalidContentRate: Double = 0.0,
    val unknownResponseRate: Double = 0.0,
)

@Serializable
enum class NwcEncryptionType(val tag: String) {
    NIP04("nip04"),
    NIP44("nip44_v2"),
}
