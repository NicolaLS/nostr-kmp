package io.github.nicolals.nostr.client

/**
 * Simplified connection state for observability.
 */
sealed interface ConnectionState {
    /** Not connected to the relay. */
    data object Disconnected : ConnectionState

    /** Connection attempt in progress. */
    data object Connecting : ConnectionState

    /** Connected to the relay. */
    data object Connected : ConnectionState

    /** Connection failed. */
    data class Failed(val error: String) : ConnectionState
}
