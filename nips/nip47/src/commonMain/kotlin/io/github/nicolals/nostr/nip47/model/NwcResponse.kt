package io.github.nicolals.nostr.nip47.model

import io.github.nicolals.nostr.core.codec.JsonValue

data class NwcResponse(
    val resultType: String,
    val error: NwcError? = null,
    val result: NwcResult? = null,
)

data class NwcError(
    val code: String,
    val message: String,
)

sealed interface NwcResult

data class NwcPayResult(
    val preimage: String,
    val feesPaidMsat: Long? = null,
) : NwcResult

data class NwcInvoiceResult(
    val transaction: NwcTransaction,
) : NwcResult

data class NwcListTransactionsResult(
    val transactions: List<NwcTransaction>,
) : NwcResult

data class NwcBalanceResult(
    val balanceMsat: Long,
) : NwcResult

data class NwcGetInfoResult(
    val alias: String? = null,
    val color: String? = null,
    val pubkey: String? = null,
    val network: String? = null,
    val blockHeight: Long? = null,
    val blockHash: String? = null,
    val methods: List<String>? = null,
    val notifications: List<String>? = null,
) : NwcResult

data class NwcUnknownResult(
    val resultType: String,
    val value: JsonValue? = null,
) : NwcResult
