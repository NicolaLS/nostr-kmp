package io.github.nicolals.nostr.bench

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BenchResult(
    val scenario: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val connection: ConnectionMetrics,
    val subscriptions: List<SubscriptionMetrics>,
    val publish: PublishMetrics? = null,
    val nwc: NwcMetrics? = null,
    val errors: ErrorMetrics = ErrorMetrics(),
)

@Serializable
data class ConnectionMetrics(
    val connectAttemptAtEpochMs: Long,
    val connectedAtEpochMs: Long?,
    val connectDurationMs: Long?,
)

@Serializable
data class SubscriptionMetrics(
    val id: String,
    val subscribeAtNs: Long,
    val liveAtNs: Long? = null,
    val closedAtNs: Long? = null,
    val interruptedAtNs: Long? = null,
    val totalEvents: Long,
    val droppedEvents: Long,
    val phases: List<PhaseMetrics>,
)

@Serializable
data class PhaseMetrics(
    val name: String,
    val expectedCount: Long? = null,
    val receivedCount: Long,
    val firstReceivedNs: Long? = null,
    val lastReceivedNs: Long? = null,
    val minLagNs: Long? = null,
    val maxLagNs: Long? = null,
    val avgLagNs: Double? = null,
)

@Serializable
data class PublishMetrics(
    val total: Long,
    val accepted: Long,
    val rejected: Long,
    val timedOut: Long,
    val minLatencyNs: Long? = null,
    val maxLatencyNs: Long? = null,
    val avgLatencyNs: Double? = null,
)

@Serializable
data class ErrorMetrics(
    val parseErrors: Long = 0,
    val sendFailures: Long = 0,
    val backpressureDrops: Long = 0,
    val inputDrops: Long = 0,
)

object BenchJson {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun writeResult(path: Path, result: BenchResult) {
        path.parent?.createDirectories()
        path.writeText(json.encodeToString(result))
    }
}
