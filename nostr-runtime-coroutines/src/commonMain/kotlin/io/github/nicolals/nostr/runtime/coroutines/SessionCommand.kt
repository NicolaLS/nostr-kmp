package io.github.nicolals.nostr.runtime.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation and actor admission race through a single atomic state transition. */
internal class SessionCommand<T>(private val continuation: CancellableContinuation<T>) {
    private enum class Phase { Queued, Running, Finished, Cancelled }
    private val phase = MutableStateFlow(Phase.Queued)

    init {
        continuation.invokeOnCancellation { phase.compareAndSet(Phase.Queued, Phase.Cancelled) }
    }

    fun start(): Boolean = phase.compareAndSet(Phase.Queued, Phase.Running)

    fun complete(value: T) {
        if (phase.compareAndSet(Phase.Running, Phase.Finished)) continuation.resume(value)
    }

    fun reject(reason: String) {
        while (true) {
            val current = phase.value
            if (current == Phase.Cancelled || current == Phase.Finished) return
            if (phase.compareAndSet(current, Phase.Finished)) {
                continuation.resumeWithException(IllegalStateException(reason))
                return
            }
        }
    }
}
