package io.github.nicolals.nostr.bench.legacy

import io.github.nicolals.nostr.bench.NwcEncryptionMetrics
import io.github.nicolals.nostr.bench.NwcEncryptionType
import io.github.nicolals.nostr.bench.NwcMetrics
import io.github.nicolals.nostr.bench.NwcScenario
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.UnsignedEvent
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.RelaySessionOutput
import nostr.crypto.Signer
import nostr.core.utils.hexToByteArray
import nostr.runtime.coroutines.CoroutineNostrRuntime
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val DEFAULT_NWC_CLIENT_SECRET_KEY_HEX =
    "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
internal const val DEFAULT_NWC_WALLET_SECRET_KEY_HEX =
    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"

internal fun resolveNwcClientSecretKeyHex(scenario: NwcScenario): String =
    scenario.clientSecretKeyHex ?: DEFAULT_NWC_CLIENT_SECRET_KEY_HEX

internal fun resolveNwcWalletSecretKeyHex(scenario: NwcScenario): String =
    scenario.walletSecretKeyHex ?: DEFAULT_NWC_WALLET_SECRET_KEY_HEX

data class LegacyNwcRun(
    val recorder: LegacyNwcRecorder,
    val walletRuntime: CoroutineNostrRuntime,
)

private val json = Json {
    encodeDefaults = true
    explicitNulls = true
}

suspend fun startLegacyNwcBench(
    scenario: NwcScenario,
    seed: Long,
    scope: CoroutineScope,
    clientRuntime: CoroutineNostrRuntime,
    relayUrl: String,
    connectTimeoutMs: Long,
    connectionFactory: nostr.transport.ktor.KtorRelayConnectionFactory,
    codec: nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec,
): LegacyNwcRun? {
    val clientSigner = Signer.fromPrivateKeyHex(resolveNwcClientSecretKeyHex(scenario))
    val walletSigner = Signer.fromPrivateKeyHex(resolveNwcWalletSecretKeyHex(scenario))

    val walletRuntime = CoroutineNostrRuntime(
        scope = scope,
        connectionFactory = connectionFactory,
        wireEncoder = codec,
        wireDecoder = codec,
        connectTimeoutMillis = connectTimeoutMs,
    )

    walletRuntime.connect(relayUrl)
    val connected = awaitConnected(walletRuntime.connectionSnapshots, connectTimeoutMs)
    if (!connected) {
        println("[bench-legacy] NWC wallet session failed to connect.")
        walletRuntime.shutdown()
        return null
    }

    val recorder = LegacyNwcRecorder(scenario.requestCount)
    val clientRandom = Random(seed xor 0x9E3779B97F4A7C15L)
    val walletRandom = Random(seed xor 0xC3A5C85C97CB3127L)

    val walletSubId = "nwc-wallet"
    val clientSubId = "nwc-client"

    walletRuntime.subscribe(
        walletSubId,
        filters = listOf(
            Filter(
                kinds = setOf(NWC_REQUEST_KIND),
                tags = mapOf("#p" to setOf(walletSigner.publicKey.toString())),
            ),
        ),
    )

    clientRuntime.subscribe(
        clientSubId,
        filters = listOf(
            Filter(
                kinds = setOf(NWC_RESPONSE_KIND),
                tags = mapOf("#p" to setOf(clientSigner.publicKey.toString())),
            ),
        ),
    )

    scope.launch {
        walletRuntime.outputs.collect { output ->
            if (output is RelaySessionOutput.EventReceived && output.subscriptionId.value == walletSubId) {
                handleWalletRequestLegacy(
                    event = output.event,
                    walletSigner = walletSigner,
                    clientPubkey = clientSigner.publicKey.toString(),
                    scenario = scenario,
                    random = walletRandom,
                    runtime = walletRuntime,
                )
            }
        }
    }

    scope.launch {
        sendNwcRequestsLegacy(
            scenario = scenario,
            clientSigner = clientSigner,
            walletPubkeyHex = walletSigner.publicKey.toString(),
            runtime = clientRuntime,
            recorder = recorder,
            random = clientRandom,
            scope = scope,
        )
    }

    return LegacyNwcRun(recorder, walletRuntime)
}

suspend fun handleClientResponseLegacy(
    event: Event,
    recorder: LegacyNwcRecorder,
    clientPrivateKeyHex: String,
) {
    val encryption = parseEncryption(event.tags)
    val requestId = tagValue(event.tags, "e")
    val nowNs = System.nanoTime()

    val decrypted = LegacyNwcCrypto.decrypt(
        payload = event.content,
        receiverPrivHex = clientPrivateKeyHex,
        senderPubHex = event.pubkey,
        encryption = encryption,
    )

    if (decrypted == null) {
        recorder.recordInvalid(requestId, encryption, nowNs)
        return
    }

    val isError = parseResponseHasError(decrypted)
    if (isError == null) {
        recorder.recordInvalid(requestId, encryption, nowNs)
    } else if (isError) {
        recorder.recordError(requestId, encryption, nowNs)
    } else {
        recorder.recordOk(requestId, encryption, nowNs)
    }
}

private suspend fun sendNwcRequestsLegacy(
    scenario: NwcScenario,
    clientSigner: nostr.core.crypto.Signer,
    walletPubkeyHex: String,
    runtime: CoroutineNostrRuntime,
    recorder: LegacyNwcRecorder,
    random: Random,
    scope: CoroutineScope,
) {
    val intervalNs = scenario.ratePerSec?.let { rate ->
        if (rate > 0) 1_000_000_000L / rate else 0L
    } ?: 0L
    var nextSendAt = System.nanoTime()

    val clientPrivHex = resolveNwcClientSecretKeyHex(scenario)

    for (i in 0 until scenario.requestCount) {
        val encryption = selectEncryption(random, scenario.encryption.nip04Ratio)
        val invoice = buildInvoice(random, scenario.request.invoiceSize, i)
        val requestContent = encodePayInvoiceRequest(invoice, scenario.request.amountMsat)
        val encrypted = LegacyNwcCrypto.encrypt(
            plaintext = requestContent,
            senderPrivHex = clientPrivHex,
            recipientPubHex = walletPubkeyHex,
            encryption = encryption,
        ) ?: continue

        val tags = listOf(
            listOf("p", walletPubkeyHex),
            listOf("encryption", encryption.tag),
        )
        val unsigned = UnsignedEvent(
            pubkey = clientSigner.publicKey.toString(),
            createdAt = System.currentTimeMillis() / 1000,
            kind = NWC_REQUEST_KIND,
            tags = tags,
            content = encrypted,
        )
        val signed = unsigned.sign(clientSigner)
        val startNs = System.nanoTime()
        recorder.recordRequest(signed.id, encryption, startNs)
        runtime.publish(signed)
        scope.launch {
            delay(scenario.timeoutMs)
            recorder.recordTimeout(signed.id)
        }

        if (intervalNs > 0) {
            nextSendAt += intervalNs
            val delayNs = nextSendAt - System.nanoTime()
            if (delayNs > 0) {
                delay(delayNs / 1_000_000L)
            }
        }
    }
}

private suspend fun handleWalletRequestLegacy(
    event: Event,
    walletSigner: nostr.core.crypto.Signer,
    clientPubkey: String,
    scenario: NwcScenario,
    random: Random,
    runtime: CoroutineNostrRuntime,
) {
    val encryption = parseEncryption(event.tags)
    val requestId = event.id
    val decrypted = LegacyNwcCrypto.decrypt(
        payload = event.content,
        receiverPrivHex = resolveNwcWalletSecretKeyHex(scenario),
        senderPubHex = event.pubkey,
        encryption = encryption,
    ) ?: return

    if (random.nextDouble() < scenario.wallet.dropRate) return

    val method = parseRequestMethod(decrypted)
    val respondError = method != "pay_invoice" || random.nextDouble() < scenario.wallet.errorRate
    val responseContent = if (respondError) {
        encodePayInvoiceErrorResponse("PAYMENT_FAILED", "payment failed")
    } else {
        encodePayInvoiceSuccessResponse(
            preimage = randomHex(random, 64),
            feesPaidMsat = scenario.request.amountMsat?.let { max(1L, it / 100) },
        )
    }

    val encrypted = LegacyNwcCrypto.encrypt(
        plaintext = responseContent,
        senderPrivHex = resolveNwcWalletSecretKeyHex(scenario),
        recipientPubHex = event.pubkey,
        encryption = encryption,
    ) ?: return

    val tags = listOf(
        listOf("p", clientPubkey),
        listOf("e", requestId),
        listOf("d", "resp-${requestId.take(8)}"),
        listOf("encryption", encryption.tag),
    )
    var unsigned = UnsignedEvent(
        pubkey = walletSigner.publicKey.toString(),
        createdAt = System.currentTimeMillis() / 1000,
        kind = NWC_RESPONSE_KIND,
        tags = tags,
        content = encrypted,
    )
    var signed = unsigned.sign(walletSigner)

    if (random.nextDouble() < scenario.wallet.wrongTagRate) {
        signed = signed.copy(tags = mutateRequestIdTag(signed.tags, randomHex(random, 64)))
    }

    if (random.nextDouble() < scenario.wallet.invalidContentRate) {
        signed = signed.copy(content = corruptContent(signed.content))
    }

    val delayMs = randomDelayMs(random, scenario.wallet.minDelayMs, scenario.wallet.maxDelayMs)
    if (delayMs > 0) delay(delayMs)

    runtime.publish(signed)

    if (random.nextDouble() < scenario.wallet.duplicateRate) {
        if (delayMs > 0) delay(delayMs)
        runtime.publish(signed)
    }

    if (random.nextDouble() < scenario.wallet.unknownResponseRate) {
        val unknownTags = listOf(
            listOf("p", clientPubkey),
            listOf("e", randomHex(random, 64)),
            listOf("d", "unknown-${randomHex(random, 6)}"),
            listOf("encryption", encryption.tag),
        )
        val unknownUnsigned = UnsignedEvent(
            pubkey = walletSigner.publicKey.toString(),
            createdAt = System.currentTimeMillis() / 1000,
            kind = NWC_RESPONSE_KIND,
            tags = unknownTags,
            content = encrypted,
        )
        runtime.publish(unknownUnsigned.sign(walletSigner))
    }
}

private fun parseRequestMethod(payload: String): String? {
    val obj = json.parseToJsonElement(payload) as? JsonObject ?: return null
    return obj["method"]?.jsonPrimitive?.content
}

private fun parseResponseHasError(payload: String): Boolean? {
    val obj = json.parseToJsonElement(payload) as? JsonObject ?: return null
    val error = obj["error"] ?: return null
    return error !is JsonNull
}

private fun encodePayInvoiceRequest(invoice: String, amountMsat: Long?): String {
    val params = buildJsonObject {
        put("invoice", JsonPrimitive(invoice))
        amountMsat?.let { put("amount", JsonPrimitive(it)) }
    }
    val obj = buildJsonObject {
        put("method", JsonPrimitive("pay_invoice"))
        put("params", params)
    }
    return json.encodeToString(JsonObject.serializer(), obj)
}

private fun encodePayInvoiceSuccessResponse(preimage: String, feesPaidMsat: Long?): String {
    val result = buildJsonObject {
        put("preimage", JsonPrimitive(preimage))
        feesPaidMsat?.let { put("fees_paid", JsonPrimitive(it)) }
    }
    val obj = buildJsonObject {
        put("result_type", JsonPrimitive("pay_invoice"))
        put("error", JsonNull)
        put("result", result)
    }
    return json.encodeToString(JsonObject.serializer(), obj)
}

private fun encodePayInvoiceErrorResponse(code: String, message: String): String {
    val error = buildJsonObject {
        put("code", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
    }
    val obj = buildJsonObject {
        put("result_type", JsonPrimitive("pay_invoice"))
        put("error", error)
        put("result", JsonNull)
    }
    return json.encodeToString(JsonObject.serializer(), obj)
}

private fun parseEncryption(tags: List<List<String>>): NwcEncryptionType {
    val value = tagValue(tags, "encryption")?.lowercase()
    return if (value == NwcEncryptionType.NIP04.tag) NwcEncryptionType.NIP04 else NwcEncryptionType.NIP44
}

private fun tagValue(tags: List<List<String>>, name: String): String? {
    return tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
}

private fun randomAscii(random: Random, size: Int, seq: Int): String {
    if (size <= 0) return ""
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val chars = CharArray(size)
    val seeded = Random(random.nextLong() xor seq.toLong())
    for (i in 0 until size) {
        chars[i] = alphabet[seeded.nextInt(alphabet.length)]
    }
    return chars.concatToString()
}

private fun buildInvoice(random: Random, size: Int, seq: Int): String {
    val prefix = "lnbc"
    val target = max(size, prefix.length)
    val suffix = randomAscii(random, target - prefix.length, seq)
    return prefix + suffix
}

private fun randomHex(random: Random, length: Int): String {
    if (length <= 0) return ""
    val alphabet = "0123456789abcdef"
    val chars = CharArray(length)
    for (i in 0 until length) {
        chars[i] = alphabet[random.nextInt(alphabet.length)]
    }
    return chars.concatToString()
}

private fun randomDelayMs(random: Random, minDelay: Long, maxDelay: Long): Long {
    return when {
        maxDelay > minDelay -> {
            val range = maxDelay - minDelay + 1
            minDelay + random.nextLong(range)
        }
        else -> minDelay
    }
}

private fun corruptContent(content: String): String {
    return if (content.length <= 2) "${content}x" else content.dropLast(2)
}

private fun mutateRequestIdTag(tags: List<List<String>>, newRequestId: String): List<List<String>> {
    var replaced = false
    val updated = tags.map { tag ->
        if (tag.size >= 2 && tag[0] == "e") {
            replaced = true
            listOf("e", newRequestId)
        } else {
            tag
        }
    }
    return if (replaced) updated else updated + listOf(listOf("e", newRequestId))
}

private fun selectEncryption(random: Random, nip04Ratio: Double): NwcEncryptionType {
    return if (random.nextDouble() < nip04Ratio) NwcEncryptionType.NIP04 else NwcEncryptionType.NIP44
}

private object LegacyNwcCrypto {
    fun encrypt(
        plaintext: String,
        senderPrivHex: String,
        recipientPubHex: String,
        encryption: NwcEncryptionType,
    ): String? = when (encryption) {
        NwcEncryptionType.NIP04 -> LegacyNip04Bridge.encrypt(plaintext, senderPrivHex, recipientPubHex)
        NwcEncryptionType.NIP44 -> LegacyNip44Bridge.encrypt(plaintext, senderPrivHex, recipientPubHex)
    }

    fun decrypt(
        payload: String,
        receiverPrivHex: String,
        senderPubHex: String,
        encryption: NwcEncryptionType,
    ): String? = when (encryption) {
        NwcEncryptionType.NIP04 -> LegacyNip04Bridge.decrypt(payload, receiverPrivHex, senderPubHex)
        NwcEncryptionType.NIP44 -> LegacyNip44Bridge.decrypt(payload, receiverPrivHex, senderPubHex)
    }
}

private object LegacyNip04Bridge {
    private val clazz by lazy { Class.forName("Nip04Kt") }
    private val encryptMethod by lazy {
        clazz.getMethod(
            "encrypt",
            String::class.java,
            String::class.java,
            String::class.java,
            ByteArray::class.java,
        )
    }
    private val decryptMethod by lazy {
        clazz.getMethod(
            "decrypt",
            String::class.java,
            String::class.java,
            String::class.java,
        )
    }
    private val ivMethod by lazy { clazz.getMethod("generateInitializationVector") }

    fun encrypt(plaintext: String, senderPrivHex: String, recipientPubHex: String): String? {
        return runCatching {
            val iv = ivMethod.invoke(null) as ByteArray
            encryptMethod.invoke(null, plaintext, senderPrivHex, recipientPubHex, iv) as String
        }.getOrNull()
    }

    fun decrypt(payload: String, receiverPrivHex: String, senderPubHex: String): String? {
        return runCatching {
            decryptMethod.invoke(null, payload, receiverPrivHex, senderPubHex) as String
        }.getOrNull()
    }
}

private object LegacyNip44Bridge {
    private val clazz by lazy { Class.forName("LibKt") }
    private val ensureMethod by lazy { clazz.getMethod("ensureSodium") }
    private val conversationKeyMethod by lazy {
        clazz.getMethod(
            "calculateConversationKey",
            ByteArray::class.java,
            ByteArray::class.java,
        )
    }
    private val encryptMethod by lazy {
        clazz.getMethod(
            "encrypt",
            String::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
        )
    }
    private val decryptMethod by lazy {
        clazz.getMethod(
            "decrypt",
            String::class.java,
            ByteArray::class.java,
        )
    }
    private val nonceMethod by lazy { clazz.getMethod("generateNonce") }

    fun encrypt(plaintext: String, senderPrivHex: String, recipientPubHex: String): String? {
        return runCatching {
            ensureMethod.invoke(null)
            val conversationKey = conversationKeyMethod.invoke(
                null,
                senderPrivHex.hexToByteArray(),
                recipientPubHex.hexToByteArray(),
            ) as ByteArray
            val nonce = nonceMethod.invoke(null) as ByteArray
            encryptMethod.invoke(null, plaintext, conversationKey, nonce) as String
        }.getOrNull()
    }

    fun decrypt(payload: String, receiverPrivHex: String, senderPubHex: String): String? {
        return runCatching {
            ensureMethod.invoke(null)
            val conversationKey = conversationKeyMethod.invoke(
                null,
                receiverPrivHex.hexToByteArray(),
                senderPubHex.hexToByteArray(),
            ) as ByteArray
            decryptMethod.invoke(null, payload, conversationKey) as String
        }.getOrNull()
    }
}

class LegacyNwcRecorder(private val totalRequests: Int) {
    private data class RequestMeta(val startNs: Long, val encryption: NwcEncryptionType)

    private val pending = ConcurrentHashMap<String, RequestMeta>()
    private val completed = ConcurrentHashMap.newKeySet<String>()
    private val timedOut = ConcurrentHashMap.newKeySet<String>()

    private val sentRequests = AtomicLong(0)
    private val responses = AtomicLong(0)
    private val responsesOk = AtomicLong(0)
    private val responsesError = AtomicLong(0)
    private val responsesInvalid = AtomicLong(0)
    private val responsesUnmatched = AtomicLong(0)
    private val responsesDuplicate = AtomicLong(0)
    private val timeouts = AtomicLong(0)

    private val nip04Requests = AtomicLong(0)
    private val nip44Requests = AtomicLong(0)
    private val nip04Responses = AtomicLong(0)
    private val nip44Responses = AtomicLong(0)

    private val latencyMin = AtomicLong(Long.MAX_VALUE)
    private val latencyMax = AtomicLong(0)
    private val latencySum = AtomicLong(0)
    private val latencyCount = AtomicLong(0)

    fun recordRequest(id: String, encryption: NwcEncryptionType, startNs: Long) {
        pending[id] = RequestMeta(startNs, encryption)
        sentRequests.incrementAndGet()
        when (encryption) {
            NwcEncryptionType.NIP04 -> nip04Requests.incrementAndGet()
            NwcEncryptionType.NIP44 -> nip44Requests.incrementAndGet()
        }
    }

    fun recordTimeout(id: String) {
        val removed = pending.remove(id)
        if (removed != null) {
            timedOut.add(id)
            timeouts.incrementAndGet()
        }
    }

    fun recordOk(id: String?, encryption: NwcEncryptionType, nowNs: Long) {
        recordResponse(id, encryption, ResponseKind.OK, nowNs)
    }

    fun recordError(id: String?, encryption: NwcEncryptionType, nowNs: Long) {
        recordResponse(id, encryption, ResponseKind.ERROR, nowNs)
    }

    fun recordInvalid(id: String?, encryption: NwcEncryptionType, nowNs: Long) {
        recordResponse(id, encryption, ResponseKind.INVALID, nowNs)
    }

    private fun recordResponse(id: String?, encryption: NwcEncryptionType, kind: ResponseKind, nowNs: Long) {
        responses.incrementAndGet()
        when (encryption) {
            NwcEncryptionType.NIP04 -> nip04Responses.incrementAndGet()
            NwcEncryptionType.NIP44 -> nip44Responses.incrementAndGet()
        }

        if (id == null) {
            responsesUnmatched.incrementAndGet()
            if (kind == ResponseKind.INVALID) responsesInvalid.incrementAndGet()
            return
        }

        val meta = pending.remove(id)
        if (meta != null) {
            val latency = nowNs - meta.startNs
            updateLatency(latency)
            completed.add(id)
            when (kind) {
                ResponseKind.OK -> responsesOk.incrementAndGet()
                ResponseKind.ERROR -> responsesError.incrementAndGet()
                ResponseKind.INVALID -> responsesInvalid.incrementAndGet()
            }
            return
        }

        if (timedOut.remove(id)) {
            responsesDuplicate.incrementAndGet()
            if (kind == ResponseKind.INVALID) responsesInvalid.incrementAndGet()
            return
        }

        if (completed.contains(id)) {
            responsesDuplicate.incrementAndGet()
            if (kind == ResponseKind.INVALID) responsesInvalid.incrementAndGet()
            return
        }

        responsesUnmatched.incrementAndGet()
        if (kind == ResponseKind.INVALID) responsesInvalid.incrementAndGet()
    }

    private fun updateLatency(latency: Long) {
        latencyMin.updateAndGet { min -> minOf(min, latency) }
        latencyMax.updateAndGet { max -> maxOf(max, latency) }
        latencySum.addAndGet(latency)
        latencyCount.incrementAndGet()
    }

    fun toMetrics(): NwcMetrics {
        val count = latencyCount.get()
        val avg = if (count > 0) latencySum.get().toDouble() / count.toDouble() else null
        val min = latencyMin.get().takeIf { it != Long.MAX_VALUE }
        val max = latencyMax.get().takeIf { it != 0L }

        return NwcMetrics(
            totalRequests = totalRequests.toLong(),
            sentRequests = sentRequests.get(),
            responses = responses.get(),
            responsesOk = responsesOk.get(),
            responsesError = responsesError.get(),
            responsesInvalid = responsesInvalid.get(),
            responsesUnmatched = responsesUnmatched.get(),
            responsesDuplicate = responsesDuplicate.get(),
            timeouts = timeouts.get(),
            minLatencyNs = min,
            maxLatencyNs = max,
            avgLatencyNs = avg,
            encryption = NwcEncryptionMetrics(
                nip04Requests = nip04Requests.get(),
                nip44Requests = nip44Requests.get(),
                nip04Responses = nip04Responses.get(),
                nip44Responses = nip44Responses.get(),
            ),
        )
    }

    private enum class ResponseKind { OK, ERROR, INVALID }
}

private const val NWC_REQUEST_KIND = 23194
private const val NWC_RESPONSE_KIND = 23195

private suspend fun awaitConnected(
    snapshots: StateFlow<ConnectionSnapshot>,
    timeoutMs: Long,
): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        snapshots.first { it is ConnectionSnapshot.Connected }
        true
    } ?: false
}
