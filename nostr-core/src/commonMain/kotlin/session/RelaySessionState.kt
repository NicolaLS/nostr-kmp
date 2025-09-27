package nostr.core.session

import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.model.PublishResult

/**
 * Immutable snapshot of the Nostr client state. It captures the requested connection,
 * subscription bookkeeping (including dedupe buffers), pending publishes, and the most
 * recent engine error so callers can render UI or drive custom retry logic.
 */
data class RelaySessionState(
    val desiredRelayUrl: String? = null,
    val connection: ConnectionSnapshot = ConnectionSnapshot.Disconnected,
    val subscriptions: Map<SubscriptionId, SubscriptionState> = emptyMap(),
    val pendingPublishes: List<Event> = emptyList(),
    val publishStatuses: Map<String, PublishStatus> = emptyMap(),
    val lastError: EngineError? = null
) {
    /** Returns a copy of this state with [subscriptions] mutated by [updater]. */
    fun withSubscriptions(updater: (MutableMap<SubscriptionId, SubscriptionState>) -> Unit): RelaySessionState {
        val next = subscriptions.toMutableMap()
        updater(next)
        return copy(subscriptions = next.toMap())
    }
}

/**
 * Represents a single subscription created by the client. `receivedEventIds` retains a
 * sliding window of recently emitted event IDs so duplicates can be suppressed while new
 * events continue to flow.
 */
data class SubscriptionState(
    val subscriptionId: SubscriptionId,
    val filters: List<Filter>,
    val status: SubscriptionStatus,
    val receivedEventIds: List<String> = emptyList(),
    val eoseReceived: Boolean = false
)

/** Lifecycle of a subscription from the engine's perspective. */
enum class SubscriptionStatus {
    Pending,
    Active,
    Closing,
    Closed
}

/** Tracks the relay's acknowledgement for a published event. */
sealed interface PublishStatus {
    data object Pending : PublishStatus
    data class Acknowledged(val result: PublishResult) : PublishStatus
}

/** Clears [lastError] while returning a copy of the state. */
fun RelaySessionState.clearError(): RelaySessionState = if (lastError == null) this else copy(lastError = null)
