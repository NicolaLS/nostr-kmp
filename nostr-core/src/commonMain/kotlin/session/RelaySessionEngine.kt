package nostr.core.session

/**
 * Thin wrapper around [RelaySessionReducer] that keeps the current state. Runtimes inject
 * intents via [dispatch] and execute the emitted [RelaySessionCommand]s. The class is
 * purposely lightweight so alternative runtimes can embed it easily.
 */
class RelaySessionEngine(
    settings: RelaySessionSettings = RelaySessionSettings(),
    private val reducer: RelaySessionReducer = DefaultRelaySessionReducer(settings),
    initialState: RelaySessionState = RelaySessionState()
) {
    /** The current immutable state snapshot. */
    var state: RelaySessionState = initialState
        private set

    /** Applies [intent], updates [state], and returns the transition + commands to execute. */
    fun dispatch(intent: RelaySessionIntent): EngineTransition {
        val transition = reducer.reduce(state, intent)
        state = transition.state
        return transition
    }

    /** Replaces the engine state, typically used when reconnecting or resetting tests. */
    fun reset(state: RelaySessionState = RelaySessionState()) {
        this.state = state
    }
}
