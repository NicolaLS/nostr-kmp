package io.github.nicolals.nostr.bench.legacy

import io.github.nicolals.nostr.bench.BenchJson
import io.github.nicolals.nostr.bench.BenchResult
import io.github.nicolals.nostr.bench.CliArgs
import io.github.nicolals.nostr.bench.ConnectionMetrics
import io.github.nicolals.nostr.bench.ErrorMetrics
import io.github.nicolals.nostr.bench.EventPayloadFactory
import io.github.nicolals.nostr.bench.EventStreamPlan
import io.github.nicolals.nostr.bench.FilterSpec
import io.github.nicolals.nostr.bench.PublishPlan
import io.github.nicolals.nostr.bench.Scenario
import io.github.nicolals.nostr.bench.ScenarioLoader
import io.github.nicolals.nostr.bench.resolveScenarioPath
import io.github.nicolals.nostr.bench.resolvedCount
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.Filter
import nostr.core.model.PublishResult
import nostr.core.model.UnsignedEvent
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.RelaySessionOutput
import nostr.crypto.Signer
import nostr.runtime.coroutines.CoroutineNostrRuntime
import nostr.transport.ktor.KtorRelayConnectionFactory

private const val DEFAULT_SECRET_KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

fun main(args: Array<String>) = runBlocking {
    val cli = CliArgs(args)
    val scenarioPath = cli.get("scenario") ?: "scenarios/baseline.json"
    val resultsPath = cli.get("results")

    val scenario = ScenarioLoader.load(resolveScenarioPath(scenarioPath))
    val relayUrl = "ws://${scenario.relay.host}:${scenario.relay.port}${scenario.relay.path}"

    val codec = KotlinxSerializationWireCodec.default()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connectionFactory = KtorRelayConnectionFactory(scope)
    val runtime = CoroutineNostrRuntime(
        scope = scope,
        connectionFactory = connectionFactory,
        wireEncoder = codec,
        wireDecoder = codec,
        connectTimeoutMillis = scenario.client.connectTimeoutMs,
    )

    val errorRecorder = LegacyErrorRecorder()
    val subscriptionRecorders = mutableListOf<LegacySubscriptionRecorder>()
    val recorderById = ConcurrentHashMap<String, LegacySubscriptionRecorder>()
    val nwcRecorderRef = AtomicReference<LegacyNwcRecorder?>(null)

    val publishPending = ConcurrentHashMap<String, PublishPending>()
    val publishRecorder = scenario.client.publish?.let { LegacyPublishRecorder(it.count) }
    val nwcClientKeyHex = scenario.nwc?.let { resolveNwcClientSecretKeyHex(it) }

    val outputsJob = scope.launch {
        runtime.outputs.collect { output ->
            errorRecorder.record(output)
            when (output) {
                is RelaySessionOutput.EventReceived -> {
                    if (output.subscriptionId.value == "nwc-client") {
                        val recorder = nwcRecorderRef.get()
                        if (recorder != null && nwcClientKeyHex != null) {
                            handleClientResponseLegacy(output.event, recorder, nwcClientKeyHex)
                        }
                    }
                    recorderById[output.subscriptionId.value]
                        ?.recordEvent(output.event, System.nanoTime())
                }
                is RelaySessionOutput.EndOfStoredEvents -> {
                    recorderById[output.subscriptionId.value]?.onEose()
                }
                is RelaySessionOutput.SubscriptionTerminated -> {
                    recorderById[output.subscriptionId.value]?.onClosed()
                }
                is RelaySessionOutput.ConnectionStateChanged -> {
                    if (output.snapshot is ConnectionSnapshot.Failed || output.snapshot is ConnectionSnapshot.Disconnected) {
                        subscriptionRecorders.forEach { it.onInterrupted() }
                    }
                }
                is RelaySessionOutput.PublishAcknowledged -> {
                    val pending = publishPending.remove(output.result.eventId)
                    if (pending != null) {
                        val latency = System.nanoTime() - pending.startNs
                        publishRecorder?.record(output.result, latency)
                        pending.deferred.complete(output.result)
                    }
                }
                else -> Unit
            }
        }
    }

    val startEpochMs = System.currentTimeMillis()
    val connectAttemptAt = startEpochMs
    runtime.connect(relayUrl)
    val connected = awaitConnected(runtime.connectionSnapshots, scenario.client.connectTimeoutMs)
    val connectedAt = if (connected) System.currentTimeMillis() else null

    if (!connected) {
        println("[bench-legacy] Failed to connect within ${scenario.client.connectTimeoutMs}ms")
        outputsJob.cancel()
        scope.cancel()
        return@runBlocking
    }

    if (scenario.nwc != null && !scenario.relayBehavior.forward.enabled) {
        println("[bench-legacy] Warning: relayBehavior.forward.enabled is false; NWC events may not reach the wallet.")
    }

    val nwcRun = scenario.nwc?.let { nwcScenario ->
        startLegacyNwcBench(
            scenario = nwcScenario,
            seed = scenario.seed,
            scope = scope,
            clientRuntime = runtime,
            relayUrl = relayUrl,
            connectTimeoutMs = scenario.client.connectTimeoutMs,
            connectionFactory = connectionFactory,
            codec = codec,
        )?.also { run ->
            nwcRecorderRef.set(run.recorder)
        }
    }

    val payloadFactory = EventPayloadFactory(scenario.seed, scenario.eventProfiles)
    val liveLabel = scenario.relayBehavior.subscription.live?.label
    val expectedCounts = expectedPhaseCounts(scenario)

    scenario.client.subscriptions.forEach { spec ->
        val filters = spec.filters.map { it.toLegacyFilter() }
        val subscribeAtNs = System.nanoTime()
        runtime.subscribe(spec.id, filters)
        val recorder = LegacySubscriptionRecorder(
            id = spec.id,
            subscribeAtNs = subscribeAtNs,
            livePhaseLabel = liveLabel,
            expectedCounts = expectedCounts,
        )
        subscriptionRecorders += recorder
        recorderById[spec.id] = recorder

        spec.closeAfterMs?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                runtime.unsubscribe(spec.id)
            }
        }
    }

    publishRecorder?.let {
        val signer = createSigner(scenario.client.signerSecretKeyHex ?: DEFAULT_SECRET_KEY_HEX)
        scope.launch {
            runPublishPlan(
                plan = scenario.client.publish,
                scenario = scenario,
                payloadFactory = payloadFactory,
                runtime = runtime,
                signer = signer,
                pending = publishPending,
                recorder = it,
            )
        }
    }

    val runDurationMs = scenario.client.runDurationMs ?: estimateRunDurationMs(scenario).coerceAtLeast(1_000L)
    if (runDurationMs > 0) {
        delay(runDurationMs)
    }

    nwcRun?.walletRuntime?.shutdown()
    runtime.disconnect(1000, "bench done")
    runtime.shutdown()
    outputsJob.cancel()
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
            backpressureDrops = 0,
            inputDrops = 0,
        ),
    )

    val outputPath = resultsPath ?: "results/${scenario.name}-legacy.json"
    BenchJson.writeResult(kotlin.io.path.Path(outputPath), result)
    println("[bench-legacy] Results written to $outputPath")
}

private data class PublishPending(
    val startNs: Long,
    val deferred: CompletableDeferred<PublishResult>,
)

private suspend fun awaitConnected(
    snapshots: StateFlow<ConnectionSnapshot>,
    timeoutMs: Long,
): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        snapshots.first { it is ConnectionSnapshot.Connected }
        true
    } ?: false
}

private fun FilterSpec.toLegacyFilter(): Filter {
    val tagMap = tagFilters?.mapKeys { (key, _) -> if (key.startsWith("#")) key else "#$key" }
        ?.mapValues { (_, values) -> values.toSet() }
        ?: emptyMap()

    return Filter(
        ids = ids?.map { it.lowercase() }?.toSet(),
        authors = authors?.map { it.lowercase() }?.toSet(),
        kinds = kinds?.toSet(),
        since = since,
        until = until,
        limit = limit,
        tags = tagMap,
    )
}

private fun createSigner(secretKeyHex: String): nostr.core.crypto.Signer {
    return Signer.fromPrivateKeyHex(secretKeyHex)
}

private suspend fun runPublishPlan(
    plan: PublishPlan?,
    scenario: Scenario,
    payloadFactory: EventPayloadFactory,
    runtime: CoroutineNostrRuntime,
    signer: nostr.core.crypto.Signer,
    pending: ConcurrentHashMap<String, PublishPending>,
    recorder: LegacyPublishRecorder,
) {
    if (plan == null) return
    val intervalNs = plan.ratePerSec?.let { rate ->
        if (rate > 0) 1_000_000_000L / rate else 0L
    } ?: 0L
    var nextSendAt = System.nanoTime()

    val pubkeyHex = signer.publicKey.toString()

    for (i in 0 until plan.count) {
        val offsetNs = intervalNs * i.toLong()
        val payload = payloadFactory.payload(plan.profile, i.toLong(), "publish", offsetNs)
        val unsigned = UnsignedEvent(
            pubkey = pubkeyHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = payload.kind,
            tags = payload.tags,
            content = payload.content,
        )
        val event = unsigned.sign(signer)

        val startNs = System.nanoTime()
        val deferred = CompletableDeferred<PublishResult>()
        pending[event.id] = PublishPending(startNs, deferred)

        runtime.publish(event)
        val result = withTimeoutOrNull(5_000) { deferred.await() }
        val latency = result?.let { System.nanoTime() - startNs }
        recorder.record(result, latency)
        if (result == null) {
            pending.remove(event.id)
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
