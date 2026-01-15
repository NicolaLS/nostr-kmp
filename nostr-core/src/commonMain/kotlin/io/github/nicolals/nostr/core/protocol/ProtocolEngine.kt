package io.github.nicolals.nostr.core.protocol

/**
 * The protocol engine processes inputs through registered reducers.
 *
 * The engine is a pure, deterministic state machine. It takes the current
 * state and an input, runs the input through all registered reducers,
 * and produces a new state with any effects and signals.
 *
 * ## Processing Order
 *
 * Reducers are processed in registration order. Each reducer that handles
 * the input can modify the state. Effects and signals from all reducers
 * are collected and returned together.
 *
 * A reducer can set `consumed = true` in its result to stop further
 * processing. This is useful for gating behavior (e.g., auth required
 * before subscribe).
 *
 * ## Thread Safety
 *
 * The engine itself is stateless and thread-safe. State is passed in
 * and returned; the engine doesn't hold any mutable state.
 *
 * ## Example
 *
 * ```kotlin
 * val engine = ProtocolEngine.Builder()
 *     .register(ConnectionReducer())
 *     .register(Nip01Reducer(codec))
 *     .build()
 *
 * var state = ProtocolState()
 *
 * // Process transport opened
 * val result = engine.process(state, TransportInput.Opened)
 * state = result.state
 * // Execute effects, surface signals...
 *
 * // Process inbound message
 * val msgResult = engine.process(state, InboundMessage(message))
 * state = msgResult.state
 * // Execute effects, surface signals...
 * ```
 */
class ProtocolEngine internal constructor(
    private val reducers: List<ProtocolReducer>,
) {
    /**
     * Processes an input through all reducers and produces a result.
     *
     * @param state The current protocol state.
     * @param input The input to process.
     * @return The result containing the new state, collected effects, and signals.
     */
    fun process(state: ProtocolState, input: ProtocolInput): ReduceResult {
        var currentState = state
        val allEffects = mutableListOf<ProtocolEffect>()
        val allSignals = mutableListOf<ProtocolSignal>()

        for (reducer in reducers) {
            val result = reducer.reduce(currentState, input) ?: continue

            currentState = result.state
            allEffects += result.effects
            allSignals += result.signals

            if (result.consumed) {
                break
            }
        }

        return ReduceResult(
            state = currentState,
            effects = allEffects,
            signals = allSignals,
        )
    }

    /**
     * Builder for creating [ProtocolEngine] instances.
     */
    class Builder {
        private val reducers = mutableListOf<ProtocolReducer>()

        /**
         * Registers a reducer.
         *
         * Reducers are processed in registration order.
         */
        fun register(reducer: ProtocolReducer) = apply {
            reducers += reducer
        }

        /**
         * Builds the engine with all registered reducers.
         */
        fun build(): ProtocolEngine = ProtocolEngine(reducers.toList())
    }
}
