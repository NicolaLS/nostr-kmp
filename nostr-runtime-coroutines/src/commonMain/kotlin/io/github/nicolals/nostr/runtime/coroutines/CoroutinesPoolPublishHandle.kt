package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Aggregated publish handle for a relay pool.
 *
 * Provides a per-relay status map and helpers to await completion.
 */
class CoroutinesPoolPublishHandle internal constructor(
    val eventId: EventId,
    val handles: Map<RelayUrl, CoroutinesPublishHandle>,
    scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()

    private val _statusFlow = MutableStateFlow(
        handles.mapValues { it.value.status }
    )

    /**
     * Current per-relay status map.
     */
    val status: Map<RelayUrl, PublishStatus>
        get() = _statusFlow.value

    /**
     * Observable per-relay status map.
     */
    val statusFlow: StateFlow<Map<RelayUrl, PublishStatus>> = _statusFlow.asStateFlow()

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
     * Suspends until all publishes reach a terminal status.
     */
    suspend fun awaitAll(): Map<RelayUrl, PublishStatus> = coroutineScope {
        val deferred = handles.mapValues { (_, handle) ->
            async { handle.await() }
        }
        deferred.mapValues { (_, result) -> result.await() }
    }

    /**
     * Stops status tracking for this handle.
     */
    fun close() {
        jobs.forEach { it.cancel() }
    }
}
