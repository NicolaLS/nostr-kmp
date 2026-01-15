package io.github.nicolals.nostr.core.protocol

/**
 * Result of processing an input through a reducer.
 *
 * Contains the new state and any effects/signals produced.
 *
 * @property state The new protocol state after processing.
 * @property effects Effects for the runtime to execute (send messages, timers).
 * @property signals Signals for consumers to observe (status changes, events).
 * @property consumed If true, stop processing further reducers for this input.
 */
data class ReduceResult(
    val state: ProtocolState,
    val effects: List<ProtocolEffect> = emptyList(),
    val signals: List<ProtocolSignal> = emptyList(),
    val consumed: Boolean = false,
) {
    companion object {
        /**
         * Creates a result with just state changes, no effects or signals.
         */
        fun stateOnly(state: ProtocolState): ReduceResult =
            ReduceResult(state)

        /**
         * Creates a result with state and effects.
         */
        fun withEffects(
            state: ProtocolState,
            vararg effects: ProtocolEffect,
        ): ReduceResult = ReduceResult(state, effects.toList())

        /**
         * Creates a result with state and signals.
         */
        fun withSignals(
            state: ProtocolState,
            vararg signals: ProtocolSignal,
        ): ReduceResult = ReduceResult(state, signals = signals.toList())

        /**
         * Creates a result with state, effects, and signals.
         */
        fun withEffectsAndSignals(
            state: ProtocolState,
            effects: List<ProtocolEffect>,
            signals: List<ProtocolSignal>,
        ): ReduceResult = ReduceResult(state, effects, signals)
    }
}

/**
 * Processes protocol inputs and produces state transitions with effects/signals.
 *
 * Reducers implement NIP-specific protocol semantics. They are pure functions
 * that take the current state and an input, and produce a new state with
 * any effects and signals.
 *
 * ## Reducer Composition
 *
 * Multiple reducers can process the same input. The engine runs reducers
 * in registration order, threading state through each. A reducer can:
 * - Return `null` to indicate it doesn't handle the input
 * - Return a result with `consumed = false` to allow other reducers to also process
 * - Return a result with `consumed = true` to stop further processing
 *
 * ## Purity
 *
 * Reducers must be pure: no I/O, no side effects, deterministic output
 * for the same input. All side effects are expressed via [ProtocolEffect].
 *
 * ## Example
 *
 * ```kotlin
 * class MyReducer : ProtocolReducer {
 *     override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
 *         return when (input) {
 *             is MyIntent -> handleMyIntent(state, input)
 *             else -> null // Don't handle other inputs
 *         }
 *     }
 * }
 * ```
 */
interface ProtocolReducer {
    /**
     * Processes an input and optionally produces a new state with effects/signals.
     *
     * @param state The current protocol state.
     * @param input The input to process.
     * @return The result containing new state, effects, and signals, or null
     *   if this reducer does not handle the input.
     */
    fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult?
}
