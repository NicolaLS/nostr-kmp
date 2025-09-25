package nostr.core.session

import nostr.core.model.ClientMessage

/**
 * Side effects requested by the reducer. A runtime executes commands in order and feeds the
 * results back into the engine as intents. Keeping commands explicit makes the state machine
 * deterministic and easy to reason about.
 */
sealed interface RelaySessionCommand {
    /** Open the WebSocket at [url]. */
    data class OpenConnection(val url: String) : RelaySessionCommand

    /** Close the WebSocket using the optional close [code] and [reason]. */
    data class CloseConnection(val code: Int?, val reason: String?) : RelaySessionCommand

    /** Send a canonical Nostr [message] to the relay. */
    data class SendToRelay(val message: ClientMessage) : RelaySessionCommand

    /** Emit a [RelaySessionOutput] downstream to application code. */
    data class EmitOutput(val output: RelaySessionOutput) : RelaySessionCommand
}
