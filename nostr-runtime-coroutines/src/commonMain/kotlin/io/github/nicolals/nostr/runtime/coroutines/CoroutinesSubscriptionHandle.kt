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
 * The session configures buffer capacity and overflow policy. By default overflow
 * closes the subscription and fails [events] with [SubscriptionBufferOverflowException].
 * DropNewest is opt-in; [droppedEventsFlow] retains the cumulative loss count.
 */
class CoroutinesSubscriptionHandle internal constructor(
    override val subscriptionId: SubscriptionId,
    private val closeCallback: (CoroutinesSubscriptionHandle) -> Unit,
    bufferCapacity: Int = 256,
    private val onBackpressure: ((SubscriptionId, EventId, Long) -> Unit)? = null,
) : SubscriptionHandle {

    private val _statusFlow = MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.Starting)
    private val _droppedEvents = MutableStateFlow(0L)
    val droppedEventsFlow: StateFlow<Long> = _droppedEvents.asStateFlow()

    private val eventChannel = Channel<Event>(capacity = bufferCapacity)

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
     * completes when the subscription is closed. Interruptions and retries keep
     * the flow open. Each event is delivered to one collector; use shareIn if
     * multiple observers need every event.
     *
     * ## Backpressure Handling
     *
     * Overflow fails the flow by default. If DropNewest is explicitly selected,
     * [droppedEventsFlow] reports loss even if nobody was observing session errors.
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
        closeCallback(this)
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
            _droppedEvents.value += 1
            onBackpressure?.invoke(subscriptionId, event.id, _droppedEvents.value)
            return false
        }
        return true
    }

    /**
     * Closes the event channel.
     *
     * Internal use only - called by the session when the subscription ends.
     */
    internal fun failOverflow() {
        eventChannel.close(SubscriptionBufferOverflowException(subscriptionId))
        updateStatus(SubscriptionStatus.Closed("Subscription event buffer exhausted"))
    }

    internal fun closeEventChannel() {
        eventChannel.close()
    }
}

/** Buffered events can be read, but the subscription result is incomplete. */
class SubscriptionBufferOverflowException(val subscriptionId: SubscriptionId) :
    IllegalStateException("Subscription event buffer exhausted")
