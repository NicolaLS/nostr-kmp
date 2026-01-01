package io.github.nicolals.nostr.nip47

import io.github.nicolals.nostr.core.codec.*
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.nip47.model.NwcBalanceResult
import io.github.nicolals.nostr.nip47.model.NwcError
import io.github.nicolals.nostr.nip47.model.NwcGetBalanceRequest
import io.github.nicolals.nostr.nip47.model.NwcGetInfoRequest
import io.github.nicolals.nostr.nip47.model.NwcGetInfoResult
import io.github.nicolals.nostr.nip47.model.NwcInvoiceRequestItem
import io.github.nicolals.nostr.nip47.model.NwcInvoiceResult
import io.github.nicolals.nostr.nip47.model.NwcKeysendRequestItem
import io.github.nicolals.nostr.nip47.model.NwcListTransactionsRequest
import io.github.nicolals.nostr.nip47.model.NwcListTransactionsResult
import io.github.nicolals.nostr.nip47.model.NwcLookupInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMakeInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMultiPayInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMultiPayKeysendRequest
import io.github.nicolals.nostr.nip47.model.NwcNotification
import io.github.nicolals.nostr.nip47.model.NwcPayInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcPayKeysendRequest
import io.github.nicolals.nostr.nip47.model.NwcPayResult
import io.github.nicolals.nostr.nip47.model.NwcPaymentReceivedNotification
import io.github.nicolals.nostr.nip47.model.NwcPaymentSentNotification
import io.github.nicolals.nostr.nip47.model.NwcRequest
import io.github.nicolals.nostr.nip47.model.NwcResponse
import io.github.nicolals.nostr.nip47.model.NwcResult
import io.github.nicolals.nostr.nip47.model.NwcTlvRecord
import io.github.nicolals.nostr.nip47.model.NwcTransaction
import io.github.nicolals.nostr.nip47.model.NwcUnknownNotification
import io.github.nicolals.nostr.nip47.model.NwcUnknownRequest
import io.github.nicolals.nostr.nip47.model.NwcUnknownResult

fun NipModuleContext.encodeNwcRequest(request: NwcRequest): String =
    jsonCodec.stringify(request.toJsonObject())

fun NipModuleContext.decodeNwcRequestOrNull(content: String): NwcRequest? =
    jsonCodec.parseObjectOrNull(content)?.let(::parseNwcRequest)

fun NipModuleContext.encodeNwcResponse(response: NwcResponse): String =
    jsonCodec.stringify(response.toJsonObject())

fun NipModuleContext.decodeNwcResponseOrNull(content: String): NwcResponse? =
    jsonCodec.parseObjectOrNull(content)?.let(::parseNwcResponse)

fun NipModuleContext.encodeNwcNotification(notification: NwcNotification): String =
    jsonCodec.stringify(notification.toJsonObject())

fun NipModuleContext.decodeNwcNotificationOrNull(content: String): NwcNotification? =
    jsonCodec.parseObjectOrNull(content)?.let(::parseNwcNotification)

private fun NwcRequest.toJsonObject(): JsonObject = when (this) {
    is NwcPayInvoiceRequest -> requestObject(
        method,
        jsonObjectOf(
            "invoice" to JsonString(invoice),
            "amount" to amountMsat?.let { JsonNumber(it.toString()) },
            "metadata" to metadata,
        ),
    )

    is NwcMultiPayInvoiceRequest -> requestObject(
        method,
        jsonObjectOf(
            "invoices" to JsonArray(invoices.map(::invoiceItemToJson)),
        ),
    )

    is NwcPayKeysendRequest -> requestObject(
        method,
        jsonObjectOf(
            "amount" to JsonNumber(amountMsat.toString()),
            "pubkey" to JsonString(pubkey),
            "preimage" to preimage?.let { JsonString(it) },
            "tlv_records" to tlvRecords?.let { JsonArray(it.map(::tlvRecordToJson)) },
        ),
    )

    is NwcMultiPayKeysendRequest -> requestObject(
        method,
        jsonObjectOf(
            "keysends" to JsonArray(keysends.map(::keysendItemToJson)),
        ),
    )

    is NwcMakeInvoiceRequest -> requestObject(
        method,
        jsonObjectOf(
            "amount" to JsonNumber(amountMsat.toString()),
            "description" to description?.let { JsonString(it) },
            "description_hash" to descriptionHash?.let { JsonString(it) },
            "expiry" to expirySeconds?.let { JsonNumber(it.toString()) },
            "metadata" to metadata,
        ),
    )

    is NwcLookupInvoiceRequest -> requestObject(
        method,
        jsonObjectOf(
            "payment_hash" to paymentHash?.let { JsonString(it) },
            "invoice" to invoice?.let { JsonString(it) },
        ),
    )

    is NwcListTransactionsRequest -> requestObject(
        method,
        jsonObjectOf(
            "from" to from?.let { JsonNumber(it.toString()) },
            "until" to until?.let { JsonNumber(it.toString()) },
            "limit" to limit?.let { JsonNumber(it.toString()) },
            "offset" to offset?.let { JsonNumber(it.toString()) },
            "unpaid" to unpaid?.let { JsonBoolean(it) },
            "type" to type?.let { JsonString(it) },
        ),
    )

    NwcGetBalanceRequest -> requestObject(method, JsonObject(emptyMap()))

    NwcGetInfoRequest -> requestObject(method, JsonObject(emptyMap()))

    is NwcUnknownRequest -> requestObject(method, params)
}

private fun NwcResponse.toJsonObject(): JsonObject {
    val entries = linkedMapOf<String, JsonValue>()
    entries["result_type"] = JsonString(resultType)
    entries["error"] = error?.let { errorToJson(it) } ?: JsonNull
    entries["result"] = result?.toJsonValue() ?: JsonNull
    return JsonObject(entries)
}

private fun NwcResult.toJsonValue(): JsonValue = when (this) {
    is NwcPayResult -> jsonObjectOf(
        "preimage" to JsonString(preimage),
        "fees_paid" to feesPaidMsat?.let { JsonNumber(it.toString()) },
    )

    is NwcInvoiceResult -> transactionToJson(transaction)

    is NwcListTransactionsResult -> jsonObjectOf(
        "transactions" to JsonArray(transactions.map(::transactionToJson)),
    )

    is NwcBalanceResult -> jsonObjectOf(
        "balance" to JsonNumber(balanceMsat.toString()),
    )

    is NwcGetInfoResult -> jsonObjectOf(
        "alias" to alias?.let { JsonString(it) },
        "color" to color?.let { JsonString(it) },
        "pubkey" to pubkey?.let { JsonString(it) },
        "network" to network?.let { JsonString(it) },
        "block_height" to blockHeight?.let { JsonNumber(it.toString()) },
        "block_hash" to blockHash?.let { JsonString(it) },
        "methods" to methods?.let { JsonArray(it.map(::JsonString)) },
        "notifications" to notifications?.let { JsonArray(it.map(::JsonString)) },
    )

    is NwcUnknownResult -> value ?: JsonNull
}

private fun NwcNotification.toJsonObject(): JsonObject {
    val notificationValue = when (this) {
        is NwcPaymentReceivedNotification -> transactionToJson(transaction)
        is NwcPaymentSentNotification -> transactionToJson(transaction)
        is NwcUnknownNotification -> value ?: JsonNull
    }
    return jsonObjectOf(
        "notification_type" to JsonString(notificationType),
        "notification" to notificationValue,
    )
}

private fun errorToJson(error: NwcError): JsonObject = jsonObjectOf(
    "code" to JsonString(error.code),
    "message" to JsonString(error.message),
)

private fun transactionToJson(transaction: NwcTransaction): JsonObject = jsonObjectOf(
    "type" to transaction.type?.let { JsonString(it) },
    "state" to transaction.state?.let { JsonString(it) },
    "invoice" to transaction.invoice?.let { JsonString(it) },
    "description" to transaction.description?.let { JsonString(it) },
    "description_hash" to transaction.descriptionHash?.let { JsonString(it) },
    "preimage" to transaction.preimage?.let { JsonString(it) },
    "payment_hash" to transaction.paymentHash?.let { JsonString(it) },
    "amount" to transaction.amountMsat?.let { JsonNumber(it.toString()) },
    "fees_paid" to transaction.feesPaidMsat?.let { JsonNumber(it.toString()) },
    "created_at" to transaction.createdAt?.let { JsonNumber(it.toString()) },
    "expires_at" to transaction.expiresAt?.let { JsonNumber(it.toString()) },
    "settled_at" to transaction.settledAt?.let { JsonNumber(it.toString()) },
    "metadata" to transaction.metadata,
)

private fun invoiceItemToJson(item: NwcInvoiceRequestItem): JsonObject = jsonObjectOf(
    "id" to item.id?.let { JsonString(it) },
    "invoice" to JsonString(item.invoice),
    "amount" to item.amountMsat?.let { JsonNumber(it.toString()) },
    "metadata" to item.metadata,
)

private fun keysendItemToJson(item: NwcKeysendRequestItem): JsonObject = jsonObjectOf(
    "id" to item.id?.let { JsonString(it) },
    "pubkey" to JsonString(item.pubkey),
    "amount" to JsonNumber(item.amountMsat.toString()),
    "preimage" to item.preimage?.let { JsonString(it) },
    "tlv_records" to item.tlvRecords?.let { JsonArray(it.map(::tlvRecordToJson)) },
)

private fun tlvRecordToJson(record: NwcTlvRecord): JsonObject = jsonObjectOf(
    "type" to JsonNumber(record.type.toString()),
    "value" to JsonString(record.value),
)

private fun parseNwcRequest(obj: JsonObject): NwcRequest? {
    val method = obj.stringValue("method") ?: return null
    val paramsValue = obj.valueOrNull("params")
    val params = when (paramsValue) {
        null -> JsonObject(emptyMap())
        is JsonObject -> paramsValue
        else -> return null
    }
    return when (method) {
        NwcRequest.METHOD_PAY_INVOICE ->
            parsePayInvoiceRequest(params) ?: NwcUnknownRequest(method, params)

        NwcRequest.METHOD_MULTI_PAY_INVOICE ->
            parseMultiPayInvoiceRequest(params) ?: NwcUnknownRequest(method, params)

        NwcRequest.METHOD_PAY_KEYSEND ->
            parsePayKeysendRequest(params) ?: NwcUnknownRequest(method, params)

        NwcRequest.METHOD_MULTI_PAY_KEYSEND ->
            parseMultiPayKeysendRequest(params) ?: NwcUnknownRequest(method, params)

        NwcRequest.METHOD_MAKE_INVOICE ->
            parseMakeInvoiceRequest(params) ?: NwcUnknownRequest(method, params)

        NwcRequest.METHOD_LOOKUP_INVOICE ->
            parseLookupInvoiceRequest(params) ?: NwcUnknownRequest(method, params)

        NwcRequest.METHOD_LIST_TRANSACTIONS ->
            parseListTransactionsRequest(params) ?: NwcUnknownRequest(method, params)

        NwcRequest.METHOD_GET_BALANCE -> NwcGetBalanceRequest

        NwcRequest.METHOD_GET_INFO -> NwcGetInfoRequest

        else -> NwcUnknownRequest(method, params)
    }
}

private fun parseNwcResponse(obj: JsonObject): NwcResponse? {
    val resultType = obj.stringValue("result_type") ?: return null
    val error = parseNwcErrorValue(obj.valueOrNull("error"))
    val resultValue = obj.valueOrNull("result")
    val parsedResult = parseNwcResultOrError(resultType, resultValue)
    val finalError = error ?: parsedResult.error
    return NwcResponse(resultType = resultType, error = finalError, result = parsedResult.result)
}

private fun parseNwcNotification(obj: JsonObject): NwcNotification? {
    val type = obj.stringValue("notification_type") ?: return null
    val notificationValue = obj.valueOrNull("notification")
    val notificationObj = notificationValue as? JsonObject
    return when (type) {
        NwcNotification.TYPE_PAYMENT_RECEIVED -> {
            val transaction = notificationObj?.let(::parseTransaction)
                ?: return NwcUnknownNotification(type, notificationValue)
            NwcPaymentReceivedNotification(transaction)
        }

        NwcNotification.TYPE_PAYMENT_SENT -> {
            val transaction = notificationObj?.let(::parseTransaction)
                ?: return NwcUnknownNotification(type, notificationValue)
            NwcPaymentSentNotification(transaction)
        }

        else -> NwcUnknownNotification(type, notificationValue)
    }
}

private fun parsePayInvoiceRequest(params: JsonObject): NwcPayInvoiceRequest? {
    val invoice = params.stringValue("invoice") ?: return null
    val amount = params.longValueOrNull("amount")
    if (params.valueOrNull("amount") != null && amount == null) return null
    val metadataValue = params.valueOrNull("metadata")
    val metadata = if (metadataValue == null) null else metadataValue as? JsonObject ?: return null
    return NwcPayInvoiceRequest(invoice = invoice, amountMsat = amount, metadata = metadata)
}

private fun parseMultiPayInvoiceRequest(params: JsonObject): NwcMultiPayInvoiceRequest? {
    val invoicesArray = params.arrayValue("invoices") ?: return null
    val invoices = mutableListOf<NwcInvoiceRequestItem>()
    for (value in invoicesArray.values) {
        val invoiceObj = value as? JsonObject ?: return null
        val invoice = parseInvoiceItem(invoiceObj) ?: return null
        invoices += invoice
    }
    return NwcMultiPayInvoiceRequest(invoices)
}

private fun parsePayKeysendRequest(params: JsonObject): NwcPayKeysendRequest? {
    val amount = params.longValueOrNull("amount") ?: return null
    val pubkey = params.stringValue("pubkey") ?: return null
    val preimage = params.stringValue("preimage")
    val tlvRecordsValue = params.valueOrNull("tlv_records")
    val tlvRecords = if (tlvRecordsValue == null) {
        null
    } else {
        parseTlvRecordsValue(tlvRecordsValue) ?: return null
    }
    return NwcPayKeysendRequest(
        amountMsat = amount,
        pubkey = pubkey,
        preimage = preimage,
        tlvRecords = tlvRecords,
    )
}

private fun parseMultiPayKeysendRequest(params: JsonObject): NwcMultiPayKeysendRequest? {
    val keysendsArray = params.arrayValue("keysends") ?: return null
    val keysends = mutableListOf<NwcKeysendRequestItem>()
    for (value in keysendsArray.values) {
        val keysendObj = value as? JsonObject ?: return null
        val keysend = parseKeysendItem(keysendObj) ?: return null
        keysends += keysend
    }
    return NwcMultiPayKeysendRequest(keysends)
}

private fun parseMakeInvoiceRequest(params: JsonObject): NwcMakeInvoiceRequest? {
    val amount = params.longValueOrNull("amount") ?: return null
    val description = params.stringValue("description")
    val descriptionHash = params.stringValue("description_hash")
    val expiry = params.longValueOrNull("expiry")
    if (params.valueOrNull("expiry") != null && expiry == null) return null
    val metadataValue = params.valueOrNull("metadata")
    val metadata = if (metadataValue == null) null else metadataValue as? JsonObject ?: return null
    return NwcMakeInvoiceRequest(
        amountMsat = amount,
        description = description,
        descriptionHash = descriptionHash,
        expirySeconds = expiry,
        metadata = metadata,
    )
}

private fun parseLookupInvoiceRequest(params: JsonObject): NwcLookupInvoiceRequest? {
    val paymentHash = params.stringValue("payment_hash")
    val invoice = params.stringValue("invoice")
    if (paymentHash == null && invoice == null) return null
    return NwcLookupInvoiceRequest(paymentHash = paymentHash, invoice = invoice)
}

private fun parseListTransactionsRequest(params: JsonObject): NwcListTransactionsRequest? {
    val from = params.longValueOrNull("from")
    if (params.valueOrNull("from") != null && from == null) return null
    val until = params.longValueOrNull("until")
    if (params.valueOrNull("until") != null && until == null) return null
    val limit = params.longValueOrNull("limit")
    if (params.valueOrNull("limit") != null && limit == null) return null
    val offset = params.longValueOrNull("offset")
    if (params.valueOrNull("offset") != null && offset == null) return null
    val unpaid = params.booleanValue("unpaid")
    if (params.valueOrNull("unpaid") != null && unpaid == null) return null
    val type = params.stringValue("type")
    return NwcListTransactionsRequest(
        from = from,
        until = until,
        limit = limit,
        offset = offset,
        unpaid = unpaid,
        type = type,
    )
}

private fun parseInvoiceItem(obj: JsonObject): NwcInvoiceRequestItem? {
    val invoice = obj.stringValue("invoice") ?: return null
    val id = obj.stringValue("id")
    val amount = obj.longValueOrNull("amount")
    if (obj.valueOrNull("amount") != null && amount == null) return null
    val metadataValue = obj.valueOrNull("metadata")
    val metadata = if (metadataValue == null) null else metadataValue as? JsonObject ?: return null
    return NwcInvoiceRequestItem(
        invoice = invoice,
        amountMsat = amount,
        metadata = metadata,
        id = id,
    )
}

private fun parseKeysendItem(obj: JsonObject): NwcKeysendRequestItem? {
    val pubkey = obj.stringValue("pubkey") ?: return null
    val amount = obj.longValueOrNull("amount") ?: return null
    val id = obj.stringValue("id")
    val preimage = obj.stringValue("preimage")
    val tlvRecordsValue = obj.valueOrNull("tlv_records")
    val tlvRecords = if (tlvRecordsValue == null) {
        null
    } else {
        parseTlvRecordsValue(tlvRecordsValue) ?: return null
    }
    return NwcKeysendRequestItem(
        pubkey = pubkey,
        amountMsat = amount,
        preimage = preimage,
        tlvRecords = tlvRecords,
        id = id,
    )
}

private fun parseTlvRecordsValue(value: JsonValue): List<NwcTlvRecord>? {
    val array = value as? JsonArray ?: return null
    val records = mutableListOf<NwcTlvRecord>()
    for (recordValue in array.values) {
        val recordObj = recordValue as? JsonObject ?: return null
        val record = parseTlvRecord(recordObj) ?: return null
        records += record
    }
    return records
}

private fun parseTlvRecord(obj: JsonObject): NwcTlvRecord? {
    val type = obj.longValueOrNull("type") ?: return null
    val value = obj.stringValue("value") ?: return null
    return NwcTlvRecord(type = type, value = value)
}

private fun parseNwcError(obj: JsonObject): NwcError? {
    val code = obj.stringValue("code") ?: return null
    val message = obj.stringValue("message") ?: return null
    return NwcError(code = code, message = message)
}

private data class ParsedNwcResult(
    val result: NwcResult?,
    val error: NwcError?,
)

private fun parseNwcResultOrError(resultType: String, value: JsonValue?): ParsedNwcResult {
    if (value == null) return ParsedNwcResult(null, null)
    val result = parseNwcResult(resultType, value)
    return if (result == null) {
        ParsedNwcResult(null, unexpectedError("Malformed result for $resultType"))
    } else {
        ParsedNwcResult(result, null)
    }
}

private fun parseNwcResult(resultType: String, value: JsonValue): NwcResult? {
    val obj = value as? JsonObject
    return when (resultType) {
        NwcRequest.METHOD_PAY_INVOICE,
        NwcRequest.METHOD_PAY_KEYSEND,
        NwcRequest.METHOD_MULTI_PAY_INVOICE,
        NwcRequest.METHOD_MULTI_PAY_KEYSEND ->
            obj?.let(::parsePayResult)

        NwcRequest.METHOD_MAKE_INVOICE,
        NwcRequest.METHOD_LOOKUP_INVOICE -> {
            val transaction = obj?.let(::parseTransaction) ?: return null
            NwcInvoiceResult(transaction)
        }

        NwcRequest.METHOD_LIST_TRANSACTIONS ->
            obj?.let(::parseListTransactionsResult)

        NwcRequest.METHOD_GET_BALANCE ->
            obj?.let(::parseBalanceResult)

        NwcRequest.METHOD_GET_INFO ->
            obj?.let {
                NwcGetInfoResult(
                    alias = it.stringValue("alias"),
                    color = it.stringValue("color"),
                    pubkey = it.stringValue("pubkey"),
                    network = it.stringValue("network"),
                    blockHeight = it.longValueOrNull("block_height"),
                    blockHash = it.stringValue("block_hash"),
                    methods = it.arrayValue("methods")?.stringValuesOrNull(),
                    notifications = it.arrayValue("notifications")?.stringValuesOrNull(),
                )
            }

        else -> NwcUnknownResult(resultType, value)
    }
}

private fun parsePayResult(obj: JsonObject): NwcPayResult? {
    val preimage = obj.stringValue("preimage") ?: return null
    val feesPaid = obj.longValueOrNull("fees_paid")
    if (obj.valueOrNull("fees_paid") != null && feesPaid == null) return null
    return NwcPayResult(preimage = preimage, feesPaidMsat = feesPaid)
}

private fun parseListTransactionsResult(obj: JsonObject): NwcListTransactionsResult? {
    val array = obj.arrayValue("transactions") ?: return null
    val transactions = mutableListOf<NwcTransaction>()
    for (value in array.values) {
        val txObj = value as? JsonObject ?: return null
        val transaction = parseTransaction(txObj) ?: return null
        transactions += transaction
    }
    return NwcListTransactionsResult(transactions)
}

private fun parseBalanceResult(obj: JsonObject): NwcBalanceResult? {
    val balance = obj.longValueOrNull("balance") ?: return null
    return NwcBalanceResult(balanceMsat = balance)
}

private fun parseTransaction(obj: JsonObject): NwcTransaction? {
    val type = obj.stringValue("type")
    if (obj.valueOrNull("type") != null && type == null) return null
    val state = obj.stringValue("state")
    if (obj.valueOrNull("state") != null && state == null) return null
    val invoice = obj.stringValue("invoice")
    if (obj.valueOrNull("invoice") != null && invoice == null) return null
    val description = obj.stringValue("description")
    if (obj.valueOrNull("description") != null && description == null) return null
    val descriptionHash = obj.stringValue("description_hash")
    if (obj.valueOrNull("description_hash") != null && descriptionHash == null) return null
    val preimage = obj.stringValue("preimage")
    if (obj.valueOrNull("preimage") != null && preimage == null) return null
    val paymentHash = obj.stringValue("payment_hash")
    if (obj.valueOrNull("payment_hash") != null && paymentHash == null) return null
    val amountMsat = obj.longValueOrNull("amount")
    if (obj.valueOrNull("amount") != null && amountMsat == null) return null
    val feesPaidMsat = obj.longValueOrNull("fees_paid")
    if (obj.valueOrNull("fees_paid") != null && feesPaidMsat == null) return null
    val createdAt = obj.longValueOrNull("created_at")
    if (obj.valueOrNull("created_at") != null && createdAt == null) return null
    val expiresAt = obj.longValueOrNull("expires_at")
    if (obj.valueOrNull("expires_at") != null && expiresAt == null) return null
    val settledAt = obj.longValueOrNull("settled_at")
    if (obj.valueOrNull("settled_at") != null && settledAt == null) return null
    val metadata = obj.objectValue("metadata")
    if (obj.valueOrNull("metadata") != null && metadata == null) return null
    if (!hasAnyTransactionField(obj)) return null
    return NwcTransaction(
        type = type,
        state = state,
        invoice = invoice,
        description = description,
        descriptionHash = descriptionHash,
        preimage = preimage,
        paymentHash = paymentHash,
        amountMsat = amountMsat,
        feesPaidMsat = feesPaidMsat,
        createdAt = createdAt,
        expiresAt = expiresAt,
        settledAt = settledAt,
        metadata = metadata,
    )
}

private fun hasAnyTransactionField(obj: JsonObject): Boolean {
    val fields = listOf(
        "type",
        "state",
        "invoice",
        "description",
        "description_hash",
        "preimage",
        "payment_hash",
        "amount",
        "fees_paid",
        "created_at",
        "expires_at",
        "settled_at",
        "metadata",
    )
    return fields.any { obj.valueOrNull(it) != null }
}

private fun requestObject(method: String, params: JsonObject): JsonObject = JsonObject(
    mapOf(
        "method" to JsonString(method),
        "params" to params,
    ),
)

private fun parseNwcErrorValue(value: JsonValue?): NwcError? = when (value) {
    null -> null
    is JsonObject -> parseNwcError(value) ?: unexpectedError("Malformed error object")
    else -> unexpectedError("Malformed error object")
}

private fun unexpectedError(message: String): NwcError =
    NwcError(code = "unexpected_error", message = message)
