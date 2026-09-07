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
 * Operation admission runs before all reducers, independently of registration
 * order. Reducers cannot suppress other reducers or lifecycle cleanup.
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
    private val admissions: List<OperationAdmission>,
) {
    /**
     * Processes an input through all reducers and produces a result.
     *
     * @param state The current protocol state.
     * @param input The input to process.
     * @return The result containing the new state, collected effects, and signals.
     */
    fun process(state: ProtocolState, input: ProtocolInput): ProtocolResult {
        if (input is OperationIntent) {
            var decision: AdmissionDecision = AdmissionDecision.Allow
            for (policy in admissions) {
                when (val candidate = policy.evaluate(state, input)) {
                    is AdmissionDecision.Reject -> return ProtocolResult(state, admission = candidate)
                    is AdmissionDecision.Defer -> if (decision == AdmissionDecision.Allow) decision = candidate
                    AdmissionDecision.Allow -> Unit
                }
            }
            if (decision != AdmissionDecision.Allow) return ProtocolResult(state, admission = decision)
        }
        var handled = false
        var currentState = state
        val allEffects = mutableListOf<ProtocolEffect>()
        val allSignals = mutableListOf<ProtocolSignal>()

        for (reducer in reducers) {
            val result = reducer.reduce(currentState, input) ?: continue

            handled = true
            currentState = result.state
            allEffects += result.effects
            allSignals += result.signals
        }

        if (!handled && input is ProtocolIntent) {
            return ProtocolResult(state, admission = AdmissionDecision.Reject("Unsupported intent"))
        }
        return ProtocolResult(
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
        private val admissions = mutableListOf<OperationAdmission>()

        fun admission(policy: OperationAdmission) = apply { admissions += policy }


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
        fun build(): ProtocolEngine = ProtocolEngine(reducers.toList(), admissions.toList())
    }
}
