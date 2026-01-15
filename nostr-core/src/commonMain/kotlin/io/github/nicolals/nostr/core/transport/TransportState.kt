package io.github.nicolals.nostr.core.transport

/**
 * Represents the lifecycle state of a [RelayConnection].
 *
 * State transitions follow this pattern:
 * ```
 * Idle → Connecting → Open → Closing → Closed
 *                 ↘      ↘       ↘
 *                  Failed → Closed
 * ```
 *
 * - [Idle]: Initial state, connection not started.
 * - [Connecting]: WebSocket handshake in progress.
 * - [Open]: Connection established, ready to send/receive.
 * - [Closing]: Close handshake initiated, waiting for completion.
 * - [Closed]: Connection terminated normally.
 * - [Failed]: Connection terminated due to an error.
 */
enum class TransportState {
    /**
     * Initial state. The connection has been created but [RelayConnection.connect]
     * has not been called yet.
     */
    Idle,

    /**
     * The WebSocket handshake is in progress. Transitions to [Open] on success
     * or [Failed] on error.
     */
    Connecting,

    /**
     * The connection is established and ready for communication.
     * [RelayConnection.send] is allowed.
     */
    Open,

    /**
     * A close handshake has been initiated. The connection is draining and
     * will transition to [Closed] once complete.
     */
    Closing,

    /**
     * The connection has been closed normally. This is a terminal state.
     */
    Closed,

    /**
     * The connection failed due to an error. This state is followed by
     * a transition to [Closed]. Check [RelayConnectionListener.onFailure]
     * for the error details.
     */
    Failed;

    /**
     * Whether this state allows sending messages.
     */
    val canSend: Boolean
        get() = this == Open

    /**
     * Whether this is a terminal state (Closed).
     */
    val isTerminal: Boolean
        get() = this == Closed
}
