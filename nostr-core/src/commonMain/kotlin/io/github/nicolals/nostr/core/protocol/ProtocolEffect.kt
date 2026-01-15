package io.github.nicolals.nostr.core.protocol

import io.github.nicolals.nostr.core.message.view.ClientMessageView

/**
 * Effects produced by the protocol engine for the runtime to execute.
 *
 * Effects are a sealed set to keep the runtime implementation simple.
 * The engine produces effects; the runtime executes them (sends messages,
 * schedules timers, etc.).
 *
 * Effects do not include notifications to consumers - those are delivered
 * via [ProtocolSignal].
 */
sealed interface ProtocolEffect {

    /**
     * Send a client message to the relay.
     *
     * The runtime should encode the message via the wire codec and send
     * it over the transport connection.
     *
     * @property message The client message to send.
     */
    data class Send(val message: ClientMessageView) : ProtocolEffect

    /**
     * Schedule a timer to fire after a delay.
     *
     * When the timer fires, the runtime should deliver a [TimerFired]
     * input to the engine.
     *
     * Scheduling a timer with an existing key cancels the previous timer
     * with that key.
     *
     * @property key Unique identifier for this timer.
     * @property delayMs Delay in milliseconds before the timer fires.
     */
    data class ScheduleTimer(
        val key: TimerKey,
        val delayMs: Long,
    ) : ProtocolEffect

    /**
     * Cancel a previously scheduled timer.
     *
     * If no timer with the given key exists, this is a no-op.
     *
     * @property key The timer key to cancel.
     */
    data class CancelTimer(val key: TimerKey) : ProtocolEffect
}
