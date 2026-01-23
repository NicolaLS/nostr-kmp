package io.github.nicolals.nostr.bench

import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Scenario(
    val name: String,
    val seed: Long = 0,
    val relay: RelayConfig = RelayConfig(),
    val eventProfiles: Map<String, EventProfile> = mapOf("default" to EventProfile()),
    val client: ClientConfig = ClientConfig(),
    val relayBehavior: RelayBehavior = RelayBehavior(),
    val metrics: MetricsConfig = MetricsConfig(),
    val nwc: NwcScenario? = null,
)

@Serializable
data class RelayConfig(
    val host: String = "127.0.0.1",
    val port: Int = 48080,
    val path: String = "/relay",
)

@Serializable
data class EventProfile(
    val kind: Int = 1,
    val contentSize: Int = 128,
    val tagsPerEvent: Int = 2,
    val tagValueSize: Int = 8,
    val pubkeyHex: String? = null,
)

@Serializable
data class ClientConfig(
    val connectTimeoutMs: Long = 10_000,
    val runDurationMs: Long? = null,
    val subscriptions: List<ClientSubscription> = emptyList(),
    val publish: PublishPlan? = null,
    val signerSecretKeyHex: String? = null,
)

@Serializable
data class ClientSubscription(
    val id: String,
    val filters: List<FilterSpec> = emptyList(),
    val closeAfterMs: Long? = null,
)

@Serializable
data class FilterSpec(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tagFilters: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
)

@Serializable
data class PublishPlan(
    val count: Int,
    val ratePerSec: Int? = null,
    val profile: String = "default",
)

@Serializable
data class RelayBehavior(
    val subscription: SubscriptionBehavior = SubscriptionBehavior(),
    val publish: PublishBehavior = PublishBehavior(),
    val notices: List<TimedNotice> = emptyList(),
    val disconnects: List<TimedDisconnect> = emptyList(),
    val edgeCases: RelayEdgeCases = RelayEdgeCases(),
    val forward: RelayForwarding = RelayForwarding(),
)

@Serializable
data class SubscriptionBehavior(
    val stored: EventStreamPlan? = null,
    val live: EventStreamPlan? = null,
    val sendEose: Boolean = true,
    val eoseDelayMs: Long = 0,
    val sendClosedOnReq: Boolean = false,
    val closedDelayMs: Long = 0,
    val closedMessage: String = "",
)

@Serializable
data class PublishBehavior(
    val acceptRate: Double = 1.0,
    val okDelayMs: Long = 0,
    val acceptedMessage: String = "",
    val rejectedMessage: String = "error: rejected",
)

@Serializable
data class RelayForwarding(
    val enabled: Boolean = false,
    val kinds: List<Int>? = null,
    val dropRate: Double = 0.0,
    val duplicateRate: Double = 0.0,
    val minDelayMs: Long = 0,
    val maxDelayMs: Long = 0,
)

@Serializable
data class RelayEdgeCases(
    val eoseAfterCloseMs: Long? = null,
    val eventAfterClose: EventStreamPlan? = null,
    val eventForUnknownSub: EventStreamPlan? = null,
    val eoseForUnknownSubDelayMs: Long? = null,
    val closedForUnknownSubDelayMs: Long? = null,
    val malformedFrames: List<TimedFrame> = emptyList(),
)

@Serializable
data class EventStreamPlan(
    val label: String = "stream",
    val profile: String = "default",
    val count: Int? = null,
    val durationMs: Long? = null,
    val ratePerSec: Int? = null,
    val batchSize: Int = 256,
    val startDelayMs: Long = 0,
)

@Serializable
data class TimedNotice(
    val delayMs: Long,
    val message: String,
)

@Serializable
data class TimedDisconnect(
    val delayMs: Long,
    val code: Int = 1000,
    val reason: String = "",
)

@Serializable
data class TimedFrame(
    val delayMs: Long,
    val payload: String,
)

@Serializable
data class MetricsConfig(
    val reportIntervalMs: Long = 1_000,
)

object ScenarioLoader {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun load(path: Path): Scenario {
        val raw = path.readText()
        val scenario = json.decodeFromString(Scenario.serializer(), raw)
        ScenarioValidator.validate(scenario)
        return scenario
    }
}

object ScenarioValidator {
    fun validate(scenario: Scenario) {
        require(scenario.name.isNotBlank()) { "Scenario name must not be blank." }
        require(scenario.eventProfiles.isNotEmpty()) { "At least one event profile is required." }
        scenario.client.subscriptions.forEach { sub ->
            require(sub.id.isNotBlank()) { "Subscription id must not be blank." }
            require(sub.filters.isNotEmpty()) {
                "Subscription ${sub.id} must define at least one filter."
            }
        }
        scenario.relayBehavior.subscription.stored?.let { validateStream(it, scenario) }
        scenario.relayBehavior.subscription.live?.let { validateStream(it, scenario) }
        scenario.relayBehavior.edgeCases.eventAfterClose?.let { validateStream(it, scenario) }
        scenario.relayBehavior.edgeCases.eventForUnknownSub?.let { validateStream(it, scenario) }
        scenario.client.publish?.let { publish ->
            require(publish.count > 0) { "Publish plan count must be > 0." }
            requireProfileExists(publish.profile, scenario)
        }
        scenario.nwc?.let { nwc ->
            require(nwc.requestCount > 0) { "NWC requestCount must be > 0." }
            require(nwc.ratePerSec == null || nwc.ratePerSec >= 0) {
                "NWC ratePerSec must be >= 0."
            }
            require(nwc.timeoutMs > 0) { "NWC timeoutMs must be > 0." }
            require(nwc.encryption.nip04Ratio in 0.0..1.0) {
                "NWC encryption.nip04Ratio must be between 0.0 and 1.0."
            }
            require(nwc.request.invoiceSize >= 0) { "NWC request.invoiceSize must be >= 0." }
            nwc.request.amountMsat?.let { amount ->
                require(amount >= 0) { "NWC request.amountMsat must be >= 0." }
            }
            require(nwc.wallet.minDelayMs >= 0) { "NWC wallet.minDelayMs must be >= 0." }
            require(nwc.wallet.maxDelayMs >= nwc.wallet.minDelayMs) {
                "NWC wallet.maxDelayMs must be >= wallet.minDelayMs."
            }
            require(nwc.wallet.errorRate in 0.0..1.0) { "NWC wallet.errorRate must be between 0.0 and 1.0." }
            require(nwc.wallet.dropRate in 0.0..1.0) { "NWC wallet.dropRate must be between 0.0 and 1.0." }
            require(nwc.wallet.duplicateRate in 0.0..1.0) {
                "NWC wallet.duplicateRate must be between 0.0 and 1.0."
            }
            require(nwc.wallet.wrongTagRate in 0.0..1.0) {
                "NWC wallet.wrongTagRate must be between 0.0 and 1.0."
            }
            require(nwc.wallet.invalidContentRate in 0.0..1.0) {
                "NWC wallet.invalidContentRate must be between 0.0 and 1.0."
            }
            require(nwc.wallet.unknownResponseRate in 0.0..1.0) {
                "NWC wallet.unknownResponseRate must be between 0.0 and 1.0."
            }
        }
        validateForwarding(scenario.relayBehavior.forward)
    }

    private fun validateStream(plan: EventStreamPlan, scenario: Scenario) {
        val hasCount = plan.count != null
        val hasDuration = plan.durationMs != null
        require(hasCount || hasDuration) {
            "EventStreamPlan '${plan.label}' must set count or durationMs."
        }
        if (hasDuration) {
            require(plan.ratePerSec != null && plan.ratePerSec > 0) {
                "EventStreamPlan '${plan.label}' with durationMs must set ratePerSec > 0."
            }
        }
        if (plan.count != null) {
            require(plan.count > 0) { "EventStreamPlan '${plan.label}' count must be > 0." }
        }
        if (plan.ratePerSec != null) {
            require(plan.ratePerSec >= 0) { "EventStreamPlan '${plan.label}' ratePerSec must be >= 0." }
        }
        require(plan.startDelayMs >= 0) { "EventStreamPlan '${plan.label}' startDelayMs must be >= 0." }
        requireProfileExists(plan.profile, scenario)
    }

    private fun requireProfileExists(profile: String, scenario: Scenario) {
        require(scenario.eventProfiles.containsKey(profile)) {
            "Event profile '$profile' is not defined in scenario.eventProfiles."
        }
    }

    private fun validateForwarding(forward: RelayForwarding) {
        require(forward.dropRate in 0.0..1.0) { "relayBehavior.forward.dropRate must be between 0.0 and 1.0." }
        require(forward.duplicateRate in 0.0..1.0) {
            "relayBehavior.forward.duplicateRate must be between 0.0 and 1.0."
        }
        require(forward.minDelayMs >= 0) { "relayBehavior.forward.minDelayMs must be >= 0." }
        require(forward.maxDelayMs >= forward.minDelayMs) {
            "relayBehavior.forward.maxDelayMs must be >= minDelayMs."
        }
    }
}
