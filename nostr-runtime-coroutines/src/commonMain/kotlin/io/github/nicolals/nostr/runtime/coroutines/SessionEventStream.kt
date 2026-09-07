package io.github.nicolals.nostr.runtime.coroutines

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/** An observer failed to consume signals or messages within its configured buffer. */
class SessionObservationOverflowException : IllegalStateException("Session observer buffer exhausted")

/** Broadcasts without blocking the session or silently dropping protocol responses. */
internal class SessionEventStream<T>(private val capacity: Int) {
    private data class State<T>(val closed: Boolean = false, val observers: Set<Channel<T>> = emptySet())

    private val state = MutableStateFlow(State<T>())

    val events: Flow<T> = flow {
        val channel = Channel<T>(capacity)
        while (true) {
            val current = state.value
            if (current.closed) {
                channel.close()
                break
            }
            if (state.compareAndSet(current, current.copy(observers = current.observers + channel))) break
        }
        try {
            for (value in channel) emit(value)
        } finally {
            state.update { it.copy(observers = it.observers - channel) }
            channel.cancel()
        }
    }

    fun emit(value: T) {
        for (observer in state.value.observers) {
            if (observer.trySend(value).isFailure) {
                observer.close(SessionObservationOverflowException())
                state.update { it.copy(observers = it.observers - observer) }
            }
        }
    }

    fun close() {
        while (true) {
            val current = state.value
            if (current.closed) return
            if (state.compareAndSet(current, State(closed = true))) {
                current.observers.forEach { it.close() }
                return
            }
        }
    }
}
