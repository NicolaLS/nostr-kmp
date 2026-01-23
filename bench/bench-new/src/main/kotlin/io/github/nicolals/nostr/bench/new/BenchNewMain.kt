package io.github.nicolals.nostr.bench.new

import io.github.nicolals.nostr.bench.BenchJson
import io.github.nicolals.nostr.bench.BenchResult
import io.github.nicolals.nostr.bench.CliArgs
import io.github.nicolals.nostr.bench.ConnectionMetrics
import io.github.nicolals.nostr.bench.EventPayloadFactory
import io.github.nicolals.nostr.bench.EventStreamPlan
import io.github.nicolals.nostr.bench.ErrorMetrics
import io.github.nicolals.nostr.bench.FilterSpec
import io.github.nicolals.nostr.bench.PublishPlan
import io.github.nicolals.nostr.bench.Scenario
import io.github.nicolals.nostr.bench.ScenarioLoader
import io.github.nicolals.nostr.bench.resolveScenarioPath
import io.github.nicolals.nostr.bench.resolvedCount
import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.id.EventIdComputer
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import io.github.nicolals.nostr.runtime.coroutines.SessionError
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import kotlin.io.path.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val DEFAULT_SECRET_KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
private const val FALLBACK_PUBKEY_HEX = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100"
private const val FALLBACK_SIG_HEX =
    "0000000000000000000000000000000000000000000000000000000000000000" +
    "0000000000000000000000000000000000000000000000000000000000000000"

fun main(args: Array<String>) = runBlocking {
    val cli = CliArgs(args)
    val scenarioPath = cli.get("scenario") ?: "scenarios/baseline.json"
    val resultsPath = cli.get("results")

    val scenario = ScenarioLoader.load(resolveScenarioPath(scenarioPath))
    val relayUrl = RelayUrl.parse(
        "ws://${scenario.relay.host}:${scenario.relay.port}${scenario.relay.path}"
    )

    val codec = KotlinxNostrJsonCodec()
    val ctx = SimpleNipModuleContext(codec, NostrCrypto)
    val transportFactory = KtorRelayTransportFactory()
    val additionalModules = if (scenario.nwc != null) buildNwcModules(ctx) else emptyList()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = CoroutinesRelaySession(
        url = relayUrl,
        moduleContext = ctx,
        transportFactory = transportFactory,
        scope = scope,
        additionalModules = additionalModules,
    )

    val errorRecorder = ErrorRecorder()
    val recorderById = ConcurrentHashMap<String, SubscriptionRecorder>()
    val errorJob = scope.launch {
        session.errors.collect { error ->
            errorRecorder.record(error)
            if (error is SessionError.BackpressureDropped) {
                recorderById[error.subscriptionId.value]?.recordDrop()
            }
        }
    }

    val startEpochMs = System.currentTimeMillis()
    val connectAttemptAt = startEpochMs
    session.connect()
    val connected = session.awaitConnected(scenario.client.connectTimeoutMs)
    val connectedAt = if (connected) System.currentTimeMillis() else null

    if (!connected) {
        println("[bench-new] Failed to connect within ${scenario.client.connectTimeoutMs}ms")
        scope.cancel()
        return@runBlocking
    }

    if (scenario.nwc != null && !scenario.relayBehavior.forward.enabled) {
        println("[bench-new] Warning: relayBehavior.forward.enabled is false; NWC events may not reach the wallet.")
    }

    val nwcRun = scenario.nwc?.let { nwcScenario ->
        startNwcBench(
            scenario = nwcScenario,
            seed = scenario.seed,
            ctx = ctx,
            transportFactory = transportFactory,
            scope = scope,
            clientSession = session,
            relayUrl = relayUrl,
        )
    }

    val payloadFactory = EventPayloadFactory(scenario.seed, scenario.eventProfiles)
    val subscriptionRecorders = mutableListOf<SubscriptionRecorder>()
    val liveLabel = scenario.relayBehavior.subscription.live?.label
    val expectedCounts = expectedPhaseCounts(scenario)

    scenario.client.subscriptions.forEach { spec ->
        val filters = spec.filters.map { it.toFilter() }
        val subId = SubscriptionId(spec.id)
        val subscribeAtNs = System.nanoTime()
        val handle = session.subscribe(filters, subId)

        val recorder = SubscriptionRecorder(
            id = spec.id,
            subscribeAtNs = subscribeAtNs,
            livePhaseLabel = liveLabel,
            expectedCounts = expectedCounts,
        )
        subscriptionRecorders += recorder
        recorderById[spec.id] = recorder

        scope.launch {
            handle.statusFlow.collect { status -> recorder.onStatus(status) }
        }
        scope.launch {
            handle.events.collect { event -> recorder.recordEvent(event, System.nanoTime()) }
        }

        spec.closeAfterMs?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                handle.close()
            }
        }
    }

    val publishRecorder = scenario.client.publish?.let { plan ->
        val recorder = PublishRecorder(plan.count)
        scope.launch {
            runPublishPlan(plan, scenario, payloadFactory, session, recorder)
        }
        recorder
    }

    val runDurationMs = scenario.client.runDurationMs ?: estimateRunDurationMs(scenario).coerceAtLeast(1_000L)
    if (runDurationMs > 0) {
        delay(runDurationMs)
    }

    nwcRun?.walletSession?.closeAndJoin()
    session.closeAndJoin()
    errorJob.cancel()
    scope.cancel()

    val finishedAt = System.currentTimeMillis()
    val connectionMetrics = ConnectionMetrics(
        connectAttemptAtEpochMs = connectAttemptAt,
        connectedAtEpochMs = connectedAt,
        connectDurationMs = connectedAt?.let { it - connectAttemptAt },
    )

    val result = BenchResult(
        scenario = scenario.name,
        startedAtEpochMs = startEpochMs,
        finishedAtEpochMs = finishedAt,
        connection = connectionMetrics,
        subscriptions = subscriptionRecorders.map { it.toMetrics() },
        publish = publishRecorder?.toMetrics(),
        nwc = nwcRun?.recorder?.toMetrics(),
        errors = ErrorMetrics(
            parseErrors = errorRecorder.parseErrors,
            sendFailures = errorRecorder.sendFailures,
            backpressureDrops = errorRecorder.backpressureDrops,
            inputDrops = errorRecorder.inputDrops,
        ),
    )

    val outputPath = resultsPath ?: "results/${scenario.name}-new.json"
    BenchJson.writeResult(Path(outputPath), result)
    println("[bench-new] Results written to $outputPath")
}

private fun FilterSpec.toFilter(): Filter {
    return Filter(
        ids = ids?.map { EventId.fromHex(it) },
        authors = authors?.map { PublicKey.fromHex(it) },
        kinds = kinds?.map { EventKind(it) },
        tagFilters = tagFilters,
        since = since?.let { UnixTimeSeconds(it) },
        until = until?.let { UnixTimeSeconds(it) },
        limit = limit,
    )
}

private suspend fun runPublishPlan(
    plan: PublishPlan,
    scenario: Scenario,
    payloadFactory: EventPayloadFactory,
    session: CoroutinesRelaySession,
    recorder: PublishRecorder,
) {
    val profile = scenario.eventProfiles[plan.profile]
        ?: error("Publish profile '${plan.profile}' not found.")

    val secretKeyHex = scenario.client.signerSecretKeyHex ?: DEFAULT_SECRET_KEY_HEX
    val signer = createSignerOrFallback(secretKeyHex)

    val intervalNs = plan.ratePerSec?.let { rate ->
        if (rate > 0) 1_000_000_000L / rate else 0L
    } ?: 0L
    var nextSendAt = System.nanoTime()

    for (i in 0 until plan.count) {
        val offsetNs = intervalNs * i.toLong()
        val payload = payloadFactory.payload(plan.profile, i.toLong(), "publish", offsetNs)
        val template = eventTemplate(
            kind = EventKind(profile.kind),
            content = payload.content,
            tags = payload.tags,
            createdAt = UnixTimeSeconds.now(),
        )
        val signed = signer.sign(template)
        val event = signed.event

        val startNs = System.nanoTime()
        val handle = session.publish(event)
        val status = handle.await(5_000)
        val endNs = System.nanoTime()
        val latency = if (status != null) endNs - startNs else null
        recorder.record(status, latency)

        if (intervalNs > 0) {
            nextSendAt += intervalNs
            val delayNs = nextSendAt - System.nanoTime()
            if (delayNs > 0) {
                delay(delayNs / 1_000_000L)
            }
        }
    }
}

private interface BenchSigner {
    val pubkey: PublicKey
    fun <T : EventView> sign(template: EventTemplate<T>): T
}

private fun createSignerOrFallback(secretKeyHex: String): BenchSigner {
    val signerResult = runCatching {
        NostrSigner.fromSecretKey(SecretKey.fromHex(secretKeyHex))
    }.getOrNull()

    if (signerResult is io.github.nicolals.nostr.core.crypto.CryptoResult.Ok) {
        return object : BenchSigner {
            private val delegate = signerResult.value
            override val pubkey: PublicKey get() = delegate.pubkey
            override fun <T : EventView> sign(template: EventTemplate<T>): T = delegate.sign(template)
        }
    }

    println(
        "[bench-new] Warning: crypto signer unavailable; falling back to dummy signatures for publish."
    )
    return object : BenchSigner {
        override val pubkey: PublicKey = PublicKey.fromHex(FALLBACK_PUBKEY_HEX)
        private val sig = EventSig.fromHex(FALLBACK_SIG_HEX)

        override fun <T : EventView> sign(template: EventTemplate<T>): T {
            return template.sign { draft ->
                val idBytes = EventIdComputer.nip01IdBytes(
                    pubkey = pubkey,
                    createdAt = draft.createdAt,
                    kind = draft.kind,
                    tags = draft.tags,
                    content = draft.content,
                )
                Event(
                    id = EventId.fromBytes(idBytes),
                    pubkey = pubkey,
                    createdAt = draft.createdAt,
                    kind = draft.kind,
                    tags = draft.tags,
                    content = draft.content,
                    sig = sig,
                )
            }
        }
    }
}

private fun expectedPhaseCounts(scenario: Scenario): Map<String, Long?> {
    val counts = linkedMapOf<String, Long?>()
    scenario.relayBehavior.subscription.stored?.let { plan ->
        counts[plan.label] = plan.resolvedCount().toLong()
    }
    scenario.relayBehavior.subscription.live?.let { plan ->
        counts[plan.label] = plan.resolvedCount().toLong()
    }
    scenario.relayBehavior.edgeCases.eventAfterClose?.let { plan ->
        counts[plan.label] = plan.resolvedCount().toLong()
    }
    scenario.relayBehavior.edgeCases.eventForUnknownSub?.let { plan ->
        counts[plan.label] = plan.resolvedCount().toLong()
    }
    return counts
}

private fun estimateRunDurationMs(scenario: Scenario): Long {
    val behavior = scenario.relayBehavior.subscription
    val storedDuration = estimateStreamDuration(behavior.stored)
    val liveDuration = estimateStreamDuration(behavior.live)
    val bufferMs = 2_000L
    val base = storedDuration + behavior.eoseDelayMs + liveDuration + bufferMs
    val nwcDuration = estimateNwcDurationMs(scenario)
    return maxOf(base, nwcDuration)
}

private fun estimateStreamDuration(plan: EventStreamPlan?): Long {
    if (plan == null) return 0L
    val durationMs = plan.durationMs
    if (durationMs != null) return durationMs + plan.startDelayMs
    val count = plan.count
    val ratePerSec = plan.ratePerSec
    if (count != null && ratePerSec != null && ratePerSec > 0) {
        val duration = (count.toDouble() / ratePerSec.toDouble()) * 1000.0
        return duration.toLong() + plan.startDelayMs
    }
    return plan.startDelayMs
}

private fun estimateNwcDurationMs(scenario: Scenario): Long {
    val nwc = scenario.nwc ?: return 0L
    val rate = nwc.ratePerSec
    val sendDuration = if (rate != null && rate > 0) {
        ((nwc.requestCount.toDouble() / rate.toDouble()) * 1000.0).toLong()
    } else {
        0L
    }
    val forwardDelay = scenario.relayBehavior.forward.maxDelayMs
    val bufferMs = 1_000L
    return sendDuration + nwc.wallet.maxDelayMs + forwardDelay + nwc.timeoutMs + bufferMs
}
