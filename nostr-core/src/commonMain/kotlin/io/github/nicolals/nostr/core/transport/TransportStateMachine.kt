package io.github.nicolals.nostr.core.transport

/**
 * Helper class for managing [TransportState] transitions in [RelayConnection] implementations.
 *
 * Validates that state transitions follow the legal state machine:
 * ```
 * Idle → Connecting → Open → Closing → Closed
 *                 ↘      ↘       ↘
 *                  Failed → Closed
 * ```
 *
 * ## Thread Safety
 *
 * This class is **not thread-safe**. Implementations should ensure that
 * [transition] is called from a single thread (typically the transport's
 * internal event loop).
 *
 * ## Usage
 *
 * ```kotlin
 * class MyRelayConnection : RelayConnection {
 *     private val stateMachine = TransportStateMachine()
 *
 *     override val state: TransportState
 *         get() = stateMachine.state
 *
 *     override fun connect() {
 *         check(stateMachine.transition(TransportState.Connecting)) {
 *             "Cannot connect: already ${stateMachine.state}"
 *         }
 *         // ... start websocket handshake
 *     }
 * }
 * ```
 *
 * @param initial The initial state. Default is [TransportState.Idle].
 */
class TransportStateMachine(initial: TransportState = TransportState.Idle) {

    private var _state: TransportState = initial

    /**
     * The current state.
     */
    val state: TransportState
        get() = _state

    /**
     * Attempts to transition to a new state.
     *
     * @param to The target state.
     * @return `true` if the transition was valid and applied, `false` if invalid.
     */
    fun transition(to: TransportState): Boolean {
        if (!isValidTransition(_state, to)) {
            return false
        }
        _state = to
        return true
    }

    /**
     * Transitions to a new state, throwing if the transition is invalid.
     *
     * @param to The target state.
     * @throws IllegalStateException if the transition is not valid.
     */
    fun transitionOrThrow(to: TransportState) {
        check(transition(to)) {
            "Invalid state transition: $_state → $to"
        }
    }

    /**
     * Transitions to [TransportState.Failed] then immediately to [TransportState.Closed].
     *
     * This is a convenience for handling failures, which always follow the
     * pattern: current → Failed → Closed.
     *
     * @return `true` if both transitions succeeded, `false` if already closed.
     */
    fun transitionToFailedThenClosed(): Boolean {
        // If already closed, nothing to do
        if (_state == TransportState.Closed) {
            return false
        }
        // Any non-terminal state can go to Failed
        if (_state != TransportState.Failed) {
            _state = TransportState.Failed
        }
        _state = TransportState.Closed
        return true
    }

    /**
     * Resets the state machine to [TransportState.Idle].
     *
     * This is primarily useful for testing. In production, create a new
     * [RelayConnection] instead of resetting.
     */
    fun reset() {
        _state = TransportState.Idle
    }

    companion object {
        /**
         * Valid state transitions.
         */
        private val VALID_TRANSITIONS: Map<TransportState, Set<TransportState>> = mapOf(
            TransportState.Idle to setOf(TransportState.Connecting),
            TransportState.Connecting to setOf(TransportState.Open, TransportState.Failed),
            TransportState.Open to setOf(TransportState.Closing, TransportState.Failed),
            TransportState.Closing to setOf(TransportState.Closed, TransportState.Failed),
            TransportState.Failed to setOf(TransportState.Closed),
            TransportState.Closed to emptySet(), // Terminal state
        )

        /**
         * Checks if a state transition is valid.
         *
         * @param from The current state.
         * @param to The target state.
         * @return `true` if the transition is valid.
         */
        fun isValidTransition(from: TransportState, to: TransportState): Boolean {
            return VALID_TRANSITIONS[from]?.contains(to) == true
        }
    }
}
