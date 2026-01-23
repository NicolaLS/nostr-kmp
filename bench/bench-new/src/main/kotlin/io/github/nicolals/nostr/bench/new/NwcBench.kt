package io.github.nicolals.nostr.bench.new

import io.github.nicolals.nostr.bench.NwcEncryptionMetrics
import io.github.nicolals.nostr.bench.NwcEncryptionType
import io.github.nicolals.nostr.bench.NwcMetrics
import io.github.nicolals.nostr.bench.NwcScenario
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip47.Nip47Module
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.event.NwcRequestEvent
import io.github.nicolals.nostr.nip47.event.NwcResponseEvent
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcError
import io.github.nicolals.nostr.nip47.model.NwcPayInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcPayResult
import io.github.nicolals.nostr.nip47.model.NwcResponse
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString

private const val DEFAULT_NWC_CLIENT_SECRET_KEY_HEX =
    "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
private const val DEFAULT_NWC_WALLET_SECRET_KEY_HEX =
    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"

data class NwcRun(
    val recorder: NwcRecorder,
    val walletSession: CoroutinesRelaySession,
)

fun buildNwcModules(ctx: SimpleNipModuleContext): List<Nip47Module> {
    val selector = NwcEncryptionSelector { NwcEncryption.NIP44_V2 }
    return listOf(Nip47Module(ctx, selector))
}

suspend fun startNwcBench(
    scenario: NwcScenario,
    seed: Long,
    ctx: SimpleNipModuleContext,
    transportFactory: KtorRelayTransportFactory,
    scope: CoroutineScope,
    clientSession: CoroutinesRelaySession,
    relayUrl: io.github.nicolals.nostr.core.transport.RelayUrl,
): NwcRun? {
    val clientSigner = createSignerOrNull(scenario.clientSecretKeyHex ?: DEFAULT_NWC_CLIENT_SECRET_KEY_HEX)
    val walletSigner = createSignerOrNull(scenario.walletSecretKeyHex ?: DEFAULT_NWC_WALLET_SECRET_KEY_HEX)

    if (clientSigner == null || walletSigner == null) {
        println("[bench-new] NWC signer unavailable; skipping NWC bench.")
        return null
    }

    val walletSession = CoroutinesRelaySession(
        url = relayUrl,
        moduleContext = ctx,
        transportFactory = transportFactory,
        scope = scope,
        additionalModules = buildNwcModules(ctx),
    )

    walletSession.connect()
    val walletConnected = walletSession.awaitConnected(10_000)
    if (!walletConnected) {
        println("[bench-new] NWC wallet session failed to connect.")
        walletSession.closeAndJoin()
        return null
    }

    val recorder = NwcRecorder(scenario.requestCount)
    val clientRandom = Random(seed xor 0x9E3779B97F4A7C15L)
    val walletRandom = Random(seed xor 0xC3A5C85C97CB3127L)
    val walletPubkey = walletSigner.pubkey
    val clientPubkey = clientSigner.pubkey

    val walletSub = walletSession.subscribe(
        filters = listOf(
            Filter(
                kinds = listOf(NwcRequestEvent.KIND),
                tagFilters = mapOf("p" to listOf(walletPubkey.hex)),
            ),
        ),
        subscriptionId = SubscriptionId("nwc-wallet"),
    )

    val clientSub = clientSession.subscribe(
        filters = listOf(
            Filter(
                kinds = listOf(NwcResponseEvent.KIND),
                tagFilters = mapOf("p" to listOf(clientPubkey.hex)),
            ),
        ),
        subscriptionId = SubscriptionId("nwc-client"),
    )

    scope.launch {
        walletSub.events.collect { event ->
            handleWalletRequest(
                event = event,
                ctx = ctx,
                walletSigner = walletSigner,
                walletPrivateKey = walletSigner.keys.secretKey.bytes,
                walletPubkey = walletPubkey,
                clientPubkey = clientPubkey,
                session = walletSession,
                scenario = scenario,
                random = walletRandom,
            )
        }
    }

    scope.launch {
        clientSub.events.collect { event ->
            handleClientResponse(
                event = event,
                ctx = ctx,
                clientPrivateKey = clientSigner.keys.secretKey.bytes,
                recorder = recorder,
            )
        }
    }

    scope.launch {
        sendNwcRequests(
            scenario = scenario,
            ctx = ctx,
            clientSigner = clientSigner,
            walletPubkey = walletPubkey,
            session = clientSession,
            recorder = recorder,
            random = clientRandom,
            scope = scope,
        )
    }

    return NwcRun(recorder, walletSession)
}

private suspend fun sendNwcRequests(
    scenario: NwcScenario,
    ctx: SimpleNipModuleContext,
    clientSigner: NostrSigner,
    walletPubkey: PublicKey,
    session: CoroutinesRelaySession,
    recorder: NwcRecorder,
    random: Random,
    scope: CoroutineScope,
) {
    val intervalNs = scenario.ratePerSec?.let { rate ->
        if (rate > 0) 1_000_000_000L / rate else 0L
    } ?: 0L
    var nextSendAt = System.nanoTime()

    for (i in 0 until scenario.requestCount) {
        val encryption = selectEncryption(random, scenario.encryption.nip04Ratio)
        val invoice = buildInvoice(random, scenario.request.invoiceSize, i)
        val request = NwcPayInvoiceRequest(
            invoice = invoice,
            amountMsat = scenario.request.amountMsat,
        )
        val templateResult = NwcRequestEvent.template(
            walletPubkey = walletPubkey,
            request = request,
            createdAt = UnixTimeSeconds.now(),
            ctx = ctx,
            clientPrivateKey = clientSigner.keys.secretKey.bytes,
            encryption = encryption.toNip47(),
        )

        if (templateResult is CryptoResult.Ok) {
            val signed = clientSigner.sign(templateResult.value)
            val startNs = System.nanoTime()
            recorder.recordRequest(signed.event.id.hex, encryption, startNs)
            session.publish(signed.event)
            scope.launch {
                delay(scenario.timeoutMs)
                recorder.recordTimeout(signed.event.id.hex)
            }
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

private suspend fun handleWalletRequest(
    event: Event,
    ctx: SimpleNipModuleContext,
    walletSigner: NostrSigner,
    walletPrivateKey: ByteString,
    walletPubkey: PublicKey,
    clientPubkey: PublicKey,
    session: CoroutinesRelaySession,
    scenario: NwcScenario,
    random: Random,
) {
    val view = session.events.upgrade(event) as? NwcRequestEvent ?: return
    val encryption = view.encryption.toBenchType()
    val requestResult = view.decryptForWallet(ctx, walletPrivateKey)
    if (requestResult !is CryptoResult.Ok) return

    if (random.nextDouble() < scenario.wallet.dropRate) {
        return
    }

    val respondError = random.nextDouble() < scenario.wallet.errorRate
    val response = if (respondError) {
        NwcResponse(
            resultType = requestResult.value.method,
            error = NwcError(code = "PAYMENT_FAILED", message = "payment failed"),
            result = null,
        )
    } else {
        NwcResponse(
            resultType = requestResult.value.method,
            error = null,
            result = NwcPayResult(
                preimage = randomHex(random, 64),
                feesPaidMsat = scenario.request.amountMsat?.let { max(1L, it / 100) },
            ),
        )
    }

    val templateResult = NwcResponseEvent.template(
        clientPubkey = clientPubkey,
        response = response,
        createdAt = UnixTimeSeconds.now(),
        ctx = ctx,
        walletPrivateKey = walletPrivateKey,
        encryption = encryption.toNip47(),
        requestEventId = view.event.id,
        responseId = "resp-${view.event.id.hex.take(8)}",
    )

    if (templateResult !is CryptoResult.Ok) return
    var signedEvent = walletSigner.sign(templateResult.value).event

    if (random.nextDouble() < scenario.wallet.wrongTagRate) {
        signedEvent = signedEvent.copy(tags = mutateRequestIdTag(signedEvent.tags, randomHex(random, 64)))
    }

    if (random.nextDouble() < scenario.wallet.invalidContentRate) {
        signedEvent = signedEvent.copy(content = corruptContent(signedEvent.content))
    }

    val delayMs = randomDelayMs(random, scenario.wallet.minDelayMs, scenario.wallet.maxDelayMs)
    if (delayMs > 0) delay(delayMs)

    session.publish(signedEvent)

    if (random.nextDouble() < scenario.wallet.duplicateRate) {
        if (delayMs > 0) delay(delayMs)
        session.publish(signedEvent)
    }

    if (random.nextDouble() < scenario.wallet.unknownResponseRate) {
        val unknownTemplateResult = NwcResponseEvent.template(
            clientPubkey = clientPubkey,
            response = response,
            createdAt = UnixTimeSeconds.now(),
            ctx = ctx,
            walletPrivateKey = walletPrivateKey,
            encryption = encryption.toNip47(),
            requestEventId = EventId.fromHex(randomHex(random, 64)),
            responseId = "unknown-${randomHex(random, 6)}",
        )
        if (unknownTemplateResult is CryptoResult.Ok) {
            val unknownEvent = walletSigner.sign(unknownTemplateResult.value).event
            session.publish(unknownEvent)
        }
    }
}

private suspend fun handleClientResponse(
    event: Event,
    ctx: SimpleNipModuleContext,
    clientPrivateKey: ByteString,
    recorder: NwcRecorder,
) {
    val upgraded = NwcResponseEvent.fromEventOrNull(event, NwcEncryptionSelector { NwcEncryption.NIP44_V2 })
        ?: return recorder.recordInvalid(null, NwcEncryptionType.NIP44, System.nanoTime())
    val encryption = upgraded.encryption.toBenchType()
    val requestId = upgraded.requestEventId?.hex
    val nowNs = System.nanoTime()
    val responseResult = upgraded.decryptForClient(ctx, clientPrivateKey)
    if (responseResult is CryptoResult.Ok) {
        val response = responseResult.value
        if (response.error != null) {
            recorder.recordError(requestId, encryption, nowNs)
        } else {
            recorder.recordOk(requestId, encryption, nowNs)
        }
    } else {
        recorder.recordInvalid(requestId, encryption, nowNs)
    }
}

private fun selectEncryption(random: Random, nip04Ratio: Double): NwcEncryptionType {
    return if (random.nextDouble() < nip04Ratio) NwcEncryptionType.NIP04 else NwcEncryptionType.NIP44
}

private fun buildInvoice(random: Random, size: Int, seq: Int): String {
    val prefix = "lnbc"
    val target = max(size, prefix.length)
    val suffix = randomAscii(random, target - prefix.length, seq)
    return prefix + suffix
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

private fun mutateRequestIdTag(
    tags: List<List<String>>,
    newRequestId: String,
): List<List<String>> {
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

private fun NwcEncryptionType.toNip47(): NwcEncryption = when (this) {
    NwcEncryptionType.NIP04 -> NwcEncryption.NIP04
    NwcEncryptionType.NIP44 -> NwcEncryption.NIP44_V2
}

private fun NwcEncryption.toBenchType(): NwcEncryptionType = when (this) {
    NwcEncryption.NIP04 -> NwcEncryptionType.NIP04
    NwcEncryption.NIP44_V2 -> NwcEncryptionType.NIP44
}

private fun createSignerOrNull(secretKeyHex: String): NostrSigner? {
    return when (val result = NostrSigner.fromSecretKey(io.github.nicolals.nostr.core.primitives.SecretKey.fromHex(secretKeyHex))) {
        is CryptoResult.Ok -> result.value
        is CryptoResult.Err -> null
    }
}

class NwcRecorder(private val totalRequests: Int) {
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
