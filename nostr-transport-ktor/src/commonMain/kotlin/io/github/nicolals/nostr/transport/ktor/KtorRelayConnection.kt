package io.github.nicolals.nostr.transport.ktor

import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.RelayConnection
import io.github.nicolals.nostr.core.transport.RelayConnectionListener
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.github.nicolals.nostr.core.transport.TransportError
import io.github.nicolals.nostr.core.transport.TransportResult
import io.github.nicolals.nostr.core.transport.TransportState
import io.github.nicolals.nostr.core.transport.TransportStateMachine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.concurrent.Volatile

/**
 * Ktor-based implementation of [RelayConnection].
 *
 * Uses Ktor's WebSocket client to manage a single connection to a Nostr relay.
 * This implementation is single-use; after the connection is closed, create
 * a new instance to reconnect.
 *
 * ## Threading
 *
 * - [state] can be read from any thread
 * - [connect], [send], [ping], [close] should be called from a single thread
 * - Listener callbacks are invoked from the coroutine dispatcher used by the HttpClient
 *
 * @param httpClient The Ktor HTTP client with WebSocket support installed.
 * @param url The relay URL to connect to.
 * @param config Transport configuration options.
 * @param scope The coroutine scope for the connection. Defaults to a new scope
 *   with [SupervisorJob]. The scope will be cancelled when the connection closes.
 */
internal class KtorRelayConnection(
    private val httpClient: HttpClient,
    override val url: RelayUrl,
    private val config: TransportConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : RelayConnection {

    private val stateMachine = TransportStateMachine()
    private val stateMutex = Mutex()

    @Volatile
    private var listener: RelayConnectionListener? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    private var receiveJob: Job? = null
    private var sendJob: Job? = null
    private val sendChannel = Channel<OutgoingMessage>(Channel.BUFFERED)

    @Volatile
    private var cleanupStarted = false

    override val state: TransportState
        get() = stateMachine.state

    override fun setListener(listener: RelayConnectionListener?) {
        this.listener = listener
    }

    override fun connect() {
        check(stateMachine.transition(TransportState.Connecting)) {
            "Cannot connect: connection is in state ${stateMachine.state}"
        }

        scope.launch {
            try {
                val ws = withTimeoutOrNull(config.connectTimeoutMillis) {
                    httpClient.webSocketSession(url.value) {
                        config.headers.forEach { (key, value) ->
                            header(key, value)
                        }
                    }
                }

                if (ws == null) {
                    handleFailure(TransportError.ConnectionTimeout(config.connectTimeoutMillis))
                    return@launch
                }

                session = ws

                stateMutex.withLock {
                    if (!stateMachine.transition(TransportState.Open)) {
                        // Connection was closed/failed while we were connecting
                        ws.close()
                        return@launch
                    }
                }

                listener?.onOpen()

                receiveJob = launch { receiveLoop(ws) }
                sendJob = launch { sendLoop(ws) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFailure(e.toTransportError())
            }
        }
    }

    override fun send(text: String): TransportResult<Unit> {
        if (!state.canSend) {
            return TransportResult.failure(
                TransportError.InvalidState("send", state)
            )
        }

        val sent = sendChannel.trySend(OutgoingMessage.Text(text))
        return if (sent.isSuccess) {
            TransportResult.success()
        } else {
            TransportResult.failure(
                TransportError.InvalidState("send", state)
            )
        }
    }

    override fun ping(payload: okio.ByteString?): TransportResult<Unit> {
        if (!state.canSend) {
            return TransportResult.failure(
                TransportError.InvalidState("ping", state)
            )
        }

        val sent = sendChannel.trySend(OutgoingMessage.Ping(payload?.toByteArray()))
        return if (sent.isSuccess) {
            TransportResult.success()
        } else {
            TransportResult.failure(
                TransportError.InvalidState("ping", state)
            )
        }
    }

    override fun close(code: Int, reason: String?) {
        if (state.isTerminal) return

        scope.launch {
            closeInternal(code, reason, initiatedByRemote = false)
        }
    }

    private suspend fun receiveLoop(ws: DefaultClientWebSocketSession) {
        try {
            for (frame in ws.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        listener?.onMessage(frame.readText())
                    }
                    is Frame.Pong -> {
                        listener?.onPong(frame.data.toByteString())
                    }
                    is Frame.Close -> {
                        val closeReason = frame.readReason()
                        val closeInfo = CloseInfo(
                            code = closeReason?.code?.toInt() ?: CloseInfo.ABNORMAL_CLOSURE,
                            reason = closeReason?.message,
                            initiatedByRemote = true,
                        )
                        handleClosing(closeInfo)
                        return
                    }
                    else -> {
                        // Ignore binary and other frames
                    }
                }
            }

            // Channel closed - get the close reason
            val closeReason = try {
                ws.closeReason.await()
            } catch (_: Exception) {
                null
            }

            if (closeReason != null) {
                val closeInfo = CloseInfo(
                    code = closeReason.code.toInt(),
                    reason = closeReason.message,
                    initiatedByRemote = true,
                )
                handleClosing(closeInfo)
            } else {
                handleFailure(TransportError.UnexpectedClosure)
            }
        } catch (e: CancellationException) {
            // Normal cancellation during cleanup
            throw e
        } catch (e: Exception) {
            handleFailure(e.toTransportError())
        }
    }

    private suspend fun sendLoop(ws: DefaultClientWebSocketSession) {
        try {
            for (message in sendChannel) {
                if (!ws.isActive) break

                when (message) {
                    is OutgoingMessage.Text -> {
                        ws.send(Frame.Text(message.text))
                    }
                    is OutgoingMessage.Ping -> {
                        ws.send(Frame.Ping(message.payload ?: ByteArray(0)))
                    }
                }
            }
        } catch (_: ClosedSendChannelException) {
            // Channel closed, expected during shutdown
        } catch (_: CancellationException) {
            // Job cancelled, expected during shutdown
        } catch (_: Exception) {
            // Send errors are handled by receive loop detecting connection failure
        }
    }

    private suspend fun handleClosing(closeInfo: CloseInfo) {
        val shouldNotify = stateMutex.withLock {
            stateMachine.transition(TransportState.Closing)
        }
        if (shouldNotify) {
            listener?.onClosing(closeInfo)
        }
        handleClosed(closeInfo)
    }

    private suspend fun handleClosed(closeInfo: CloseInfo) {
        val shouldNotify = stateMutex.withLock {
            if (cleanupStarted) return
            cleanupStarted = true
            stateMachine.transition(TransportState.Closed)
        }

        cleanup()

        if (shouldNotify) {
            listener?.onClosed(closeInfo)
        }
    }

    private suspend fun handleFailure(error: TransportError) {
        val shouldNotify = stateMutex.withLock {
            if (cleanupStarted) return
            cleanupStarted = true
            stateMachine.transitionToFailedThenClosed()
        }

        if (shouldNotify) {
            listener?.onFailure(error)
            listener?.onClosed(
                CloseInfo(
                    code = CloseInfo.ABNORMAL_CLOSURE,
                    reason = error.toString(),
                    initiatedByRemote = false,
                )
            )
        }

        cleanup()
    }

    private suspend fun closeInternal(code: Int, reason: String?, initiatedByRemote: Boolean) {
        val shouldNotify = stateMutex.withLock {
            stateMachine.transition(TransportState.Closing)
        }

        if (shouldNotify) {
            val closeInfo = CloseInfo(code, reason, initiatedByRemote)
            listener?.onClosing(closeInfo)

            try {
                session?.close(CloseReason(code.toShort(), reason ?: ""))
            } catch (_: Exception) {
                // Ignore close errors
            }

            handleClosed(closeInfo)
        }
    }

    private suspend fun cleanup() {
        // Close the send channel to stop the send loop
        sendChannel.close()

        // Cancel the jobs
        sendJob?.cancel()
        receiveJob?.cancel()

        // Close the session
        try {
            session?.close()
        } catch (_: Exception) {
            // Ignore
        }
        session = null

        // Cancel the scope to clean up any remaining coroutines
        scope.cancel()
    }

    private fun Exception.toTransportError(): TransportError {
        val message = this.message ?: this::class.simpleName ?: "Unknown error"
        return when {
            this::class.simpleName?.contains("SSL") == true ||
                this::class.simpleName?.contains("TLS") == true ||
                this::class.simpleName?.contains("Certificate") == true -> {
                TransportError.TlsError(message, this)
            }
            this::class.simpleName?.contains("Protocol") == true -> {
                TransportError.ProtocolError(message)
            }
            else -> {
                TransportError.IoError(message, this)
            }
        }
    }

    private sealed class OutgoingMessage {
        data class Text(val text: String) : OutgoingMessage()
        data class Ping(val payload: ByteArray?) : OutgoingMessage()
    }
}
