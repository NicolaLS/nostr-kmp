package nostr.runtime.coroutines

import nostr.core.model.ClientMessage
import nostr.core.model.RelayMessage

/**
 * Hook invoked by [CoroutineNostrRuntime] as it manages a relay connection. Implementations can be
 * used for logging, metrics, tracing, or request/response mutation (for example to inject headers
 * into outbound frames). All callbacks are executed sequentially on the runtime's scope and any
 * exception (other than cancellation) is swallowed so application code continues to operate.
 */
interface CoroutineRuntimeInterceptor {
    /** Runtime is about to open a connection to [url]. */
    suspend fun onConnectionOpening(url: String) {}

    /** Runtime successfully opened the connection to [url]. */
    suspend fun onConnectionEstablished(url: String) {}

    /**
     * Transport closed the connection to [url].
     *
     * @param code numeric WebSocket close code when available.
     * @param reason optional text description supplied by the relay.
     */
    suspend fun onConnectionClosed(url: String, code: Int?, reason: String?) {}

    /** Runtime is about to send [message] to [url]. */
    suspend fun onSend(url: String, message: ClientMessage) {}

    /** Runtime decoded [message] from the relay at [url]. */
    suspend fun onMessageReceived(url: String, message: RelayMessage) {}
}
