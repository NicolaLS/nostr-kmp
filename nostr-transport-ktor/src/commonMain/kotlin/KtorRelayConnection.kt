package nostr.transport.ktor

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult

/**
 * WebSocket-backed [RelayConnection] implementation using Ktor.
 *
 * This class expects single-threaded use; state changes are unsynchronized
 * and must remain confined to the supplied [scope].
 *
 * Timeout handling is the responsibility of the caller (typically the runtime layer).
 */
class KtorRelayConnection(
    override val url: String,
    private val client: HttpClient,
    private val scope: CoroutineScope
) : RelayConnection {

    private val outbound = Channel<String>(Channel.BUFFERED)

    private var connectJob: Job? = null
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    private var session: WebSocketSession? = null

    private var listener: RelayConnectionListener? = null

    private var closed: Boolean = false

    // Ensures terminal callbacks (onClosed/onFailure) are delivered exactly once
    private var terminalCallbackDelivered: Boolean = false

    override fun connect(listener: RelayConnectionListener) {
        check(this.listener == null) { "Connection already started" }
        this.listener = listener
        connectJob = scope.launch {
            val created = try {
                client.webSocketSession(urlString = url)
            } catch (failure: Throwable) {
                deliverFailure(failure)
                return@launch
            }
            session = created
            listener.onOpen(this@KtorRelayConnection)
            writerJob = launchWriter(created)
            readerJob = launchReader(created)
        }
    }

    override fun send(frame: String): RelaySendResult {
        if (session == null || outbound.isClosedForSend) {
            return RelaySendResult.NotConnected
        }
        val result = outbound.trySend(frame)
        return when {
            result.isSuccess -> RelaySendResult.Accepted
            result.isClosed -> RelaySendResult.NotConnected
            else -> RelaySendResult.Failed(
                result.exceptionOrNull() ?: IllegalStateException("Outbound queue rejected frame")
            )
        }
    }

    override fun close(code: Int, reason: String?) {
        if (closed) return
        closed = true
        closeOutbound()
        scope.launch {
            connectJob?.cancel()
            connectJob = null
            val current = session
            session = null
            if (current != null) {
                try {
                    current.close(CloseReason(code.toShort(), reason ?: ""))
                } catch (failure: Throwable) {
                    deliverFailure(failure)
                }
            } else {
                deliverClosed(code, reason)
            }
        }
    }

    private fun CoroutineScope.launchWriter(session: WebSocketSession) = launch {
        try {
            for (payload in outbound) {
                session.send(Frame.Text(payload))
            }
        } catch (failure: Throwable) {
            if (failure !is CancellationException) {
                deliverFailure(failure)
            }
        }
    }

    private fun CoroutineScope.launchReader(session: WebSocketSession) = launch {
        try {
            for (frame in session.incoming) {
                // Capture listener reference for safe access
                val currentListener = listener ?: return@launch
                when (frame) {
                    is Frame.Text -> currentListener.onMessage(frame.readText())
                    is Frame.Close -> {
                        val reason = frame.readReason()
                        deliverClosed(reason?.code?.toInt() ?: 1000, reason?.message)
                        return@launch
                    }

                    else -> Unit
                }
            }
            deliverClosed(1000, "EOF")
        } catch (failure: Throwable) {
            if (failure !is CancellationException) {
                deliverFailure(failure)
            }
        } finally {
            cleanup()
        }
    }

    /**
     * Delivers onClosed callback exactly once, then cleans up.
     */
    private fun deliverClosed(code: Int, reason: String?) {
        if (terminalCallbackDelivered) return
        terminalCallbackDelivered = true
        listener?.onClosed(code, reason)
        cleanup()
    }

    /**
     * Delivers onFailure callback exactly once, then cleans up.
     */
    private fun deliverFailure(cause: Throwable) {
        if (terminalCallbackDelivered) return
        terminalCallbackDelivered = true
        listener?.onFailure(cause)
        cleanup()
    }

    private fun closeOutbound() {
        if (!outbound.isClosedForSend) {
            outbound.close()
        }
    }

    /**
     * Idempotent cleanup of internal resources.
     */
    private fun cleanup() {
        closeOutbound()
        writerJob?.cancel()
        writerJob = null
        readerJob?.cancel()
        readerJob = null
        connectJob = null
        session = null
        listener = null
        closed = true
    }
}

/**
 * Creates a [RelayConnectionFactory] that produces [KtorRelayConnection] instances.
 *
 * @param scope Coroutine scope for WebSocket operations.
 * @param client Ktor HTTP client configured with WebSocket support.
 */
fun KtorRelayConnectionFactory(
    scope: CoroutineScope,
    client: HttpClient = HttpClient { install(WebSockets) }
): RelayConnectionFactory = RelayConnectionFactory { url ->
    KtorRelayConnection(
        url = url,
        client = client,
        scope = scope
    )
}
