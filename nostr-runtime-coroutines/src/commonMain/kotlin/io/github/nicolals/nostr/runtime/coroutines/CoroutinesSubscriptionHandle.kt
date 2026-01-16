package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.runtime.SubscriptionHandle
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coroutines-based implementation of [SubscriptionHandle].
 *
 * Provides Flow-based observation of subscription status and events.
 *
 * ## Usage
 *
 * ```kotlin
 * val handle = session.subscribe(filters)
 *
 * // Collect events
 * handle.events.collect { event ->
 *     println("Received: ${event.id}")
 * }
 *
 * // Observe status
 * handle.statusFlow.collect { status ->
 *     when (status) {
 *         SubscriptionStatus.Live -> println("Now receiving live events")
 *         is SubscriptionStatus.Closed -> println("Closed: ${status.reason}")
 *         else -> {}
 *     }
 * }
 *
 * // Close when done
 * handle.close()
 * ```
 *
 * ## Backpressure
 *
 * Events are buffered in a channel with limited capacity. If the consumer
 * is slower than events are arriving and the buffer fills up, new events
 * will be dropped and an error will be emitted to the session's error flow.
 *
 * To avoid dropped events:
 * - Process events quickly in the collector
 * - Use `buffer()` operator if processing is slow
 * - Consider using `conflate()` if only the latest event matters
 */
class CoroutinesSubscriptionHandle internal constructor(
    override val subscriptionId: SubscriptionId,
    private val closeCallback: (SubscriptionId) -> Unit,
    private val onBackpressure: ((SubscriptionId, EventId, Long) -> Unit)? = null,
) : SubscriptionHandle {

    private val _statusFlow = MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.Starting)
    private var droppedEvents: Long = 0

    // Bounded channel for events - drops oldest on overflow to prevent blocking
    // Capacity of 256 provides reasonable buffer while limiting memory
    private val eventChannel = Channel<Event>(capacity = 256)

    /**
     * Observable status as a [StateFlow].
     *
     * Emits the current status immediately upon collection, then emits
     * on each status change.
     */
    val statusFlow: StateFlow<SubscriptionStatus> = _statusFlow.asStateFlow()

    override val status: SubscriptionStatus
        get() = _statusFlow.value

    /**
     * Flow of events received for this subscription.
     *
     * Events are emitted in order as they arrive from the relay. The flow
     * completes when the subscription is closed or interrupted.
     *
     * ## Backpressure Handling
     *
     * Events are buffered in a channel with capacity 256. If the consumer
     * cannot keep up:
     * - Events will be dropped
     * - An error will be emitted to the session's error flow
     * - The subscription continues to operate
     *
     * Consider using operators like `buffer()` or `conflate()` if your
     * processing is slow.
     */
    val events: Flow<Event> = flow {
        try {
            for (event in eventChannel) {
                emit(event)
            }
        } catch (_: ClosedReceiveChannelException) {
            // Channel closed - flow completes normally
        }
    }

    override fun close() {
        closeCallback(subscriptionId)
    }

    /**
     * Suspends until the subscription reaches [SubscriptionStatus.Live] (EOSE received).
     *
     * This is useful when you want to wait for all stored events to be received
     * before processing live events.
     *
     * @return True if Live status was reached, false if subscription ended
     *   (Closed, Interrupted) before reaching Live.
     */
    suspend fun awaitLive(): Boolean {
        val status = _statusFlow.first { it == SubscriptionStatus.Live || it.isTerminal }
        return status == SubscriptionStatus.Live
    }

    /**
     * Suspends until the subscription reaches [SubscriptionStatus.Live] or timeout occurs.
     *
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @return True if Live status was reached, false if timeout occurred or
     *   subscription ended before reaching Live.
     */
    suspend fun awaitLive(timeoutMs: Long): Boolean {
        val status = withTimeoutOrNull(timeoutMs) {
            _statusFlow.first { it == SubscriptionStatus.Live || it.isTerminal }
        }
        return status == SubscriptionStatus.Live
    }

    /**
     * Updates the status.
     *
     * Internal use only - called by the session.
     */
    internal fun updateStatus(newStatus: SubscriptionStatus) {
        _statusFlow.value = newStatus

        // Close the event channel when the subscription is terminal
        if (newStatus.isTerminal) {
            eventChannel.close()
        }
    }

    /**
     * Sends an event to the consumer.
     *
     * Internal use only - called by the session.
     *
     * @return True if the event was buffered, false if it was dropped
     *   due to backpressure (buffer full).
     */
    internal fun sendEvent(event: Event): Boolean {
        val result = eventChannel.trySend(event)
        if (!result.isSuccess) {
            // Buffer is full, event is dropped
            droppedEvents += 1
            onBackpressure?.invoke(subscriptionId, event.id, droppedEvents)
            return false
        }
        return true
    }

    /**
     * Closes the event channel.
     *
     * Internal use only - called by the session when the subscription ends.
     */
    internal fun closeEventChannel() {
        eventChannel.close()
    }
}
