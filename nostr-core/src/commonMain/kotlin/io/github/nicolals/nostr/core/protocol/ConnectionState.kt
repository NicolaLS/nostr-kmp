package io.github.nicolals.nostr.core.protocol

import io.github.nicolals.nostr.core.transport.RelayUrl

/** Identity of one opened connection. Create a fresh instance for every transport generation. */
class ConnectionContext(val relayUrl: RelayUrl)

/**
 * The phase of the connection lifecycle from the engine's perspective.
 *
 * Note: This is simpler than [io.github.nicolals.nostr.core.transport.TransportState]
 * because the engine only cares about whether messages can be sent/received.
 * The transport layer handles the detailed connection lifecycle (connecting,
 * closing, etc.).
 */
enum class ConnectionPhase {
    /**
     * Not connected to the relay.
     *
     * This is the initial state and the state after disconnect.
     */
    Disconnected,

    /**
     * Connected to the relay and ready to send/receive messages.
     */
    Open,
}

/**
 * Connection state tracked by the protocol engine.
 *
 * @property phase The current connection phase.
 */
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.Disconnected,
    val context: ConnectionContext? = null,
) {
    /**
     * Whether the connection is open and ready for communication.
     */
    val isOpen: Boolean get() = phase == ConnectionPhase.Open
}
