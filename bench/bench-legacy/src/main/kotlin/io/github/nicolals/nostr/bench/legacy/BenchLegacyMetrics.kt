package io.github.nicolals.nostr.bench.legacy

import io.github.nicolals.nostr.bench.BenchTags
import io.github.nicolals.nostr.bench.PhaseMetrics
import io.github.nicolals.nostr.bench.PublishMetrics
import io.github.nicolals.nostr.bench.SubscriptionMetrics
import nostr.core.model.Event
import nostr.core.model.PublishResult
import nostr.core.session.EngineError
import nostr.core.session.RelaySessionOutput

class LegacyPhaseStats(
    val name: String,
    val expectedCount: Long? = null,
) {
    var count: Long = 0
        private set
    private var firstNs: Long? = null
    private var lastNs: Long? = null
    private var minLagNs: Long? = null
    private var maxLagNs: Long? = null
    private var lagSum: Double = 0.0
    private var lagCount: Long = 0

    fun record(nowNs: Long, lagNs: Long?) {
        count += 1
        if (firstNs == null) {
            firstNs = nowNs
        }
        lastNs = nowNs
        if (lagNs != null) {
            minLagNs = minLagNs?.let { minOf(it, lagNs) } ?: lagNs
            maxLagNs = maxLagNs?.let { maxOf(it, lagNs) } ?: lagNs
            lagSum += lagNs.toDouble()
            lagCount += 1
        }
    }

    fun snapshot(): PhaseMetrics {
        val avgLag = if (lagCount > 0) lagSum / lagCount else null
        return PhaseMetrics(
            name = name,
            expectedCount = expectedCount,
            receivedCount = count,
            firstReceivedNs = firstNs,
            lastReceivedNs = lastNs,
            minLagNs = minLagNs,
            maxLagNs = maxLagNs,
            avgLagNs = avgLag,
        )
    }
}

class LegacySubscriptionRecorder(
    private val id: String,
    private val subscribeAtNs: Long,
    private val livePhaseLabel: String?,
    expectedCounts: Map<String, Long?>,
) {
    private val phases = expectedCounts.mapValues { LegacyPhaseStats(it.key, it.value) }.toMutableMap()
    var liveAtNs: Long? = null
        private set
    var closedAtNs: Long? = null
        private set
    var interruptedAtNs: Long? = null
        private set

    fun onEose() {
        if (liveAtNs == null) liveAtNs = System.nanoTime()
    }

    fun onClosed() {
        if (closedAtNs == null) closedAtNs = System.nanoTime()
    }

    fun onInterrupted() {
        if (interruptedAtNs == null) interruptedAtNs = System.nanoTime()
    }

    fun recordEvent(event: Event, nowNs: Long) {
        val phase = BenchTags.phase(event.tags) ?: "unknown"
        val offsetNs = BenchTags.offsetNs(event.tags)

        val phaseStats = phases.getOrPut(phase) { LegacyPhaseStats(phase, null) }
        val phaseStart = if (phase == livePhaseLabel) {
            liveAtNs ?: subscribeAtNs
        } else {
            subscribeAtNs
        }
        val lagNs = if (offsetNs != null) nowNs - (phaseStart + offsetNs) else null
        phaseStats.record(nowNs, lagNs)
    }

    fun toMetrics(): SubscriptionMetrics {
        val totalEvents = phases.values.sumOf { it.count }
        return SubscriptionMetrics(
            id = id,
            subscribeAtNs = subscribeAtNs,
            liveAtNs = liveAtNs,
            closedAtNs = closedAtNs,
            interruptedAtNs = interruptedAtNs,
            totalEvents = totalEvents,
            droppedEvents = 0,
            phases = phases.values.map { it.snapshot() },
        )
    }
}

class LegacyPublishRecorder(
    private val total: Int,
) {
    private var accepted: Long = 0
    private var rejected: Long = 0
    private var timedOut: Long = 0
    private var minLatencyNs: Long? = null
    private var maxLatencyNs: Long? = null
    private var sumLatencyNs: Double = 0.0
    private var latencyCount: Long = 0

    fun record(result: PublishResult?, latencyNs: Long?) {
        when {
            result == null -> timedOut += 1
            result.accepted -> accepted += 1
            else -> rejected += 1
        }
        if (latencyNs != null) {
            minLatencyNs = minLatencyNs?.let { minOf(it, latencyNs) } ?: latencyNs
            maxLatencyNs = maxLatencyNs?.let { maxOf(it, latencyNs) } ?: latencyNs
            sumLatencyNs += latencyNs.toDouble()
            latencyCount += 1
        }
    }

    fun toMetrics(): PublishMetrics {
        val avgLatency = if (latencyCount > 0) sumLatencyNs / latencyCount else null
        return PublishMetrics(
            total = total.toLong(),
            accepted = accepted,
            rejected = rejected,
            timedOut = timedOut,
            minLatencyNs = minLatencyNs,
            maxLatencyNs = maxLatencyNs,
            avgLatencyNs = avgLatency,
        )
    }
}

class LegacyErrorRecorder {
    var parseErrors: Long = 0
        private set
    var sendFailures: Long = 0
        private set

    fun record(output: RelaySessionOutput) {
        val error = output as? RelaySessionOutput.Error ?: return
        when (error.error) {
            is EngineError.ProtocolViolation -> parseErrors += 1
            is EngineError.OutboundFailure -> sendFailures += 1
            is EngineError.ConnectionFailure -> sendFailures += 1
        }
    }
}
