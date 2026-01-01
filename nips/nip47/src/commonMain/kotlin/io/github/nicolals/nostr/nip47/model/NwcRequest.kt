package io.github.nicolals.nostr.nip47.model

import io.github.nicolals.nostr.core.codec.JsonObject

sealed interface NwcRequest {
    val method: String

    companion object {
        const val METHOD_PAY_INVOICE = "pay_invoice"
        const val METHOD_MULTI_PAY_INVOICE = "multi_pay_invoice"
        const val METHOD_PAY_KEYSEND = "pay_keysend"
        const val METHOD_MULTI_PAY_KEYSEND = "multi_pay_keysend"
        const val METHOD_MAKE_INVOICE = "make_invoice"
        const val METHOD_LOOKUP_INVOICE = "lookup_invoice"
        const val METHOD_LIST_TRANSACTIONS = "list_transactions"
        const val METHOD_GET_BALANCE = "get_balance"
        const val METHOD_GET_INFO = "get_info"
    }
}

data class NwcPayInvoiceRequest(
    val invoice: String,
    val amountMsat: Long? = null,
    val metadata: JsonObject? = null,
) : NwcRequest {
    override val method: String = NwcRequest.METHOD_PAY_INVOICE
}

data class NwcInvoiceRequestItem(
    val invoice: String,
    val amountMsat: Long? = null,
    val metadata: JsonObject? = null,
    val id: String? = null,
)

data class NwcMultiPayInvoiceRequest(
    val invoices: List<NwcInvoiceRequestItem>,
) : NwcRequest {
    override val method: String = NwcRequest.METHOD_MULTI_PAY_INVOICE
}

data class NwcTlvRecord(
    val type: Long,
    val value: String,
)

data class NwcPayKeysendRequest(
    val amountMsat: Long,
    val pubkey: String,
    val preimage: String? = null,
    val tlvRecords: List<NwcTlvRecord>? = null,
) : NwcRequest {
    override val method: String = NwcRequest.METHOD_PAY_KEYSEND
}

data class NwcKeysendRequestItem(
    val pubkey: String,
    val amountMsat: Long,
    val preimage: String? = null,
    val tlvRecords: List<NwcTlvRecord>? = null,
    val id: String? = null,
)

data class NwcMultiPayKeysendRequest(
    val keysends: List<NwcKeysendRequestItem>,
) : NwcRequest {
    override val method: String = NwcRequest.METHOD_MULTI_PAY_KEYSEND
}

data class NwcMakeInvoiceRequest(
    val amountMsat: Long,
    val description: String? = null,
    val descriptionHash: String? = null,
    val expirySeconds: Long? = null,
    val metadata: JsonObject? = null,
) : NwcRequest {
    override val method: String = NwcRequest.METHOD_MAKE_INVOICE
}

data class NwcLookupInvoiceRequest(
    val paymentHash: String? = null,
    val invoice: String? = null,
) : NwcRequest {
    override val method: String = NwcRequest.METHOD_LOOKUP_INVOICE
}

data class NwcListTransactionsRequest(
    val from: Long? = null,
    val until: Long? = null,
    val limit: Long? = null,
    val offset: Long? = null,
    val unpaid: Boolean? = null,
    val type: String? = null,
) : NwcRequest {
    override val method: String = NwcRequest.METHOD_LIST_TRANSACTIONS
}

object NwcGetBalanceRequest : NwcRequest {
    override val method: String = NwcRequest.METHOD_GET_BALANCE
}

object NwcGetInfoRequest : NwcRequest {
    override val method: String = NwcRequest.METHOD_GET_INFO
}

data class NwcUnknownRequest(
    override val method: String,
    val params: JsonObject,
) : NwcRequest
