package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.transport.TransportError

/**
 * Status of the connection to a relay.
 *
 * Represents the high-level connection lifecycle as observed by consumers.
 * The runtime manages transitions between these states based on transport
 * events and reconnect policy.
 */
sealed interface ConnectionStatus {
    /**
     * Whether the connection is currently usable for sending messages.
     */
    val isConnected: Boolean

    /**
     * Not connected and not attempting to connect.
     *
     * Initial state before [RelaySession.connect] is called, or after
     * giving up on reconnection.
     */
    data object Disconnected : ConnectionStatus {
        override val isConnected: Boolean = false
    }

    /**
     * Attempting to establish the initial connection.
     */
    data object Connecting : ConnectionStatus {
        override val isConnected: Boolean = false
    }

    /**
     * Connected and ready to send/receive messages.
     */
    data object Connected : ConnectionStatus {
        override val isConnected: Boolean = true
    }

    /**
     * Connection was lost, attempting to reconnect.
     *
     * @property attempt The current reconnection attempt number.
     * @property delayMs Delay before the next reconnection attempt.
     * @property lastError The error that caused the disconnect, if any.
     */
    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val lastError: TransportError? = null,
    ) : ConnectionStatus {
        override val isConnected: Boolean = false
    }

    /**
     * Connection failed and reconnection was abandoned.
     *
     * This is a terminal state - the session will not automatically
     * reconnect. Call [RelaySession.connect] to try again.
     *
     * @property error The final error that caused the failure.
     * @property totalAttempts The total number of connection attempts made.
     */
    data class Failed(
        val error: TransportError?,
        val totalAttempts: Int = 1,
    ) : ConnectionStatus {
        override val isConnected: Boolean = false
    }
}
