package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.runtime.PublishHandle
import io.github.nicolals.nostr.core.runtime.PublishStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coroutines-based implementation of [PublishHandle].
 *
 * Provides Flow-based observation of publish status and a suspend
 * function to await completion.
 *
 * ## Usage
 *
 * ```kotlin
 * val handle = session.publish(event)
 *
 * // Option 1: Observe status changes
 * handle.statusFlow.collect { status ->
 *     when (status) {
 *         is PublishStatus.Accepted -> println("Success!")
 *         is PublishStatus.Rejected -> println("Failed: ${status.message}")
 *         else -> {}
 *     }
 * }
 *
 * // Option 2: Await terminal status
 * val finalStatus = handle.await()
 * ```
 */
class CoroutinesPublishHandle internal constructor(
    override val eventId: EventId,
) : PublishHandle {

    private val _statusFlow = MutableStateFlow<PublishStatus>(PublishStatus.Queued)

    /**
     * Observable status as a [StateFlow].
     *
     * Emits the current status immediately upon collection, then emits
     * on each status change.
     */
    val statusFlow: StateFlow<PublishStatus> = _statusFlow.asStateFlow()

    override val status: PublishStatus
        get() = _statusFlow.value

    /**
     * Suspends until the publish reaches a terminal status.
     *
     * Terminal statuses are: [PublishStatus.Accepted], [PublishStatus.Rejected],
     * [PublishStatus.Abandoned], or [PublishStatus.Replaced].
     *
     * @return The terminal status.
     */
    suspend fun await(): PublishStatus {
        return _statusFlow.first { it.isTerminal }
    }

    /**
     * Suspends until the publish reaches a terminal status or timeout occurs.
     *
     * Terminal statuses are: [PublishStatus.Accepted], [PublishStatus.Rejected],
     * [PublishStatus.Abandoned], or [PublishStatus.Replaced].
     *
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @return The terminal status, or null if timeout occurred.
     */
    suspend fun await(timeoutMs: Long): PublishStatus? {
        return withTimeoutOrNull(timeoutMs) {
            _statusFlow.first { it.isTerminal }
        }
    }

    /**
     * Updates the status.
     *
     * Internal use only - called by the session.
     */
    internal fun updateStatus(newStatus: PublishStatus) {
        _statusFlow.value = newStatus
    }
}
