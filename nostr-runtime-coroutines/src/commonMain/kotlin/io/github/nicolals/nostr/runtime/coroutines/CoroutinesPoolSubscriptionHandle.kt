package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.runtime.DeduplicationPolicy
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Aggregated subscription handle for a relay pool.
 *
 * Provides merged events with optional deduplication across relays and
 * a status map for per-relay subscription states.
 */
class CoroutinesPoolSubscriptionHandle internal constructor(
    val subscriptionId: SubscriptionId,
    val handles: Map<RelayUrl, CoroutinesSubscriptionHandle>,
    deduplicationPolicy: DeduplicationPolicy,
    scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()
    private val deduplication = deduplicationPolicy

    private val _statusFlow = MutableStateFlow(
        handles.mapValues { it.value.status }
    )

    /**
     * Current per-relay status map.
     */
    val status: Map<RelayUrl, SubscriptionStatus>
        get() = _statusFlow.value

    /**
     * Observable per-relay status map.
     */
    val statusFlow: StateFlow<Map<RelayUrl, SubscriptionStatus>> = _statusFlow.asStateFlow()

    /**
     * Merged event flow across relays.
     *
     * Events are deduplicated using the provided policy.
     */
    val events: Flow<Event> = if (handles.isEmpty()) {
        emptyFlow()
    } else {
        merge(*handles.values.map { it.events }.toTypedArray())
            .transform { event ->
                if (!deduplication.isDuplicate(event.id)) {
                    emit(event)
                }
            }
            .onCompletion {
                deduplication.clear()
            }
    }

    init {
        for ((url, handle) in handles) {
            val job = scope.launch {
                handle.statusFlow.collect { status ->
                    _statusFlow.update { current ->
                        current + (url to status)
                    }
                }
            }
            jobs.add(job)
        }
    }

    /**
     * Closes all underlying subscriptions and stops status tracking.
     */
    fun close() {
        handles.values.forEach { it.close() }
        jobs.forEach { it.cancel() }
    }
}
