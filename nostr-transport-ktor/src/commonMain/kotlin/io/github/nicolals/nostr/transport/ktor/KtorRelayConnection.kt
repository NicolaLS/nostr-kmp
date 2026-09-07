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
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import kotlin.concurrent.Volatile

/** Single-use connection. All terminal paths close resources before onClosed. */
internal class KtorRelayConnection(
    private val httpClient: HttpClient,
    override val url: RelayUrl,
    private val config: TransportConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val onClosed: () -> Unit = {},
) : RelayConnection {
    private val currentState = MutableStateFlow(TransportState.Idle)
    private val cleanupStarted = MutableStateFlow(false)
    private val callbackMutex = Mutex()
    private val completion = CompletableDeferred<Unit>()

    @Volatile
    private var listener: RelayConnectionListener? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    private var connectJob: Job? = null
    private var handshakeTimeoutJob: Job? = null
    private var receiveJob: Job? = null
    private var sendJob: Job? = null
    private val sendChannel = Channel<Frame>(Channel.BUFFERED)

    override val state: TransportState get() = currentState.value

    override fun setListener(listener: RelayConnectionListener?) {
        this.listener = listener
    }

    private fun transition(to: TransportState): Boolean {
        while (true) {
            val from = currentState.value
            if (!TransportStateMachine.isValidTransition(from, to)) return false
            if (currentState.compareAndSet(from, to)) return true
        }
    }

    override fun connect() {
        check(transition(TransportState.Connecting)) { "Cannot connect: connection is in state $state" }
        handshakeTimeoutJob = scope.launch {
            delay(config.connectTimeoutMillis)
            if (state == TransportState.Connecting) fail(TransportError.ConnectionTimeout(config.connectTimeoutMillis))
        }
        connectJob = scope.launch {
            try {
                // The structured builder keeps the handshake attached to this job.
                // webSocketSession launches its request in HttpClient's own scope.
                httpClient.webSocket(url.value, request = {
                    config.headers.forEach { (key, value) -> header(key, value) }
                    if (config.subprotocols.isNotEmpty()) {
                        header("Sec-WebSocket-Protocol", config.subprotocols.joinToString(", "))
                    }
                }) {
                    val ws = this
                    session = ws
                    if (cleanupStarted.value || !transition(TransportState.Open)) {
                        ws.cancel()
                        session = null
                        return@webSocket
                    }
                    handshakeTimeoutJob?.cancel()
                    notifyActive { it.onOpen() }
                    coroutineScope {
                        receiveJob = launch { receiveLoop(ws) }
                        sendJob = launch { sendLoop(ws) }
                        joinAll(receiveJob!!, sendJob!!)
                    }
                }
            } catch (e: CancellationException) {
                if (!cleanupStarted.value) fail(TransportError.UnexpectedClosure)
                throw e
            } catch (e: Exception) {
                fail(e.toTransportError())
            }
        }
    }

    override fun send(text: String): TransportResult<Unit> = enqueue(Frame.Text(text), "send")

    override fun ping(payload: ByteString?): TransportResult<Unit> =
        enqueue(Frame.Ping(payload?.toByteArray() ?: ByteArray(0)), "ping")

    private fun enqueue(frame: Frame, operation: String): TransportResult<Unit> {
        if (!state.canSend) return TransportResult.failure(TransportError.InvalidState(operation, state))
        return if (sendChannel.trySend(frame).isSuccess) {
            TransportResult.success()
        } else {
            TransportResult.failure(TransportError.Backpressure(operation))
        }
    }

    override fun close(code: Int, reason: String?) {
        if (!transition(TransportState.Closing)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            finish(CloseInfo(code, reason, initiatedByRemote = false))
        }
    }

    internal suspend fun awaitClosed() = completion.await()

    private suspend fun notifyActive(callback: (RelayConnectionListener) -> Unit) {
        callbackMutex.withLock {
            if (!cleanupStarted.value) listener?.let(callback)
        }
    }

    private suspend fun receiveLoop(ws: DefaultClientWebSocketSession) {
        try {
            // Ktor's default session consumes control frames and exposes its final
            // closeReason separately. Pings are managed by the WebSockets plugin.
            for (frame in ws.incoming) {
                if (frame.data.size.toLong() > config.maxFrameSizeBytes) {
                    fail(TransportError.ProtocolError("Inbound frame exceeds configured limit"))
                    return
                }
                if (frame is Frame.Text) notifyActive { it.onMessage(frame.readText()) }
            }
            val reason = ws.closeReason.await()
            if (reason == null) {
                fail(TransportError.UnexpectedClosure)
            } else {
                finish(CloseInfo(reason.code.toInt(), reason.message, initiatedByRemote = true))
            }
        } catch (e: CancellationException) {
            if (!cleanupStarted.value) fail(TransportError.UnexpectedClosure)
            throw e
        } catch (e: Exception) {
            fail(e.toTransportError())
        }
    }

    private suspend fun sendLoop(ws: DefaultClientWebSocketSession) {
        try {
            for (frame in sendChannel) {
                check(ws.isActive) { "WebSocket send session is inactive" }
                ws.send(frame)
            }
        } catch (e: CancellationException) {
            if (!cleanupStarted.value) fail(TransportError.UnexpectedClosure)
            throw e
        } catch (e: Exception) {
            // A write failure need not wake the receive loop. Terminate explicitly.
            fail(e.toTransportError())
        }
    }

    private suspend fun fail(error: TransportError) {
        finish(CloseInfo(CloseInfo.ABNORMAL_CLOSURE, null, initiatedByRemote = false), error)
    }

    private suspend fun finish(info: CloseInfo, error: TransportError? = null) {
        if (!cleanupStarted.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            try {
                currentState.value = if (error == null) TransportState.Closing else TransportState.Failed
                callbackMutex.withLock {
                    if (error == null) listener?.onClosing(info) else listener?.onFailure(error)
                }
            } finally {
                // This also cancels an unfinished handshake, even with no session yet.
                connectJob?.cancel()
                handshakeTimeoutJob?.cancel()
                sendChannel.close()
                val ws = session
                session = null
                try {
                    if (ws != null) withTimeoutOrNull(2_000) {
                        if (error == null) ws.close(CloseReason(info.code.toShort(), info.reason ?: ""))
                    }
                } catch (_: Exception) {
                    // Resource release below is unconditional.
                } finally {
                    ws?.cancel()
                    sendJob?.cancel()
                    receiveJob?.cancel()
                    currentState.value = TransportState.Closed
                    try {
                        callbackMutex.withLock { listener?.onClosed(info) }
                    } finally {
                        listener = null
                        onClosed()
                        completion.complete(Unit)
                        scope.cancel()
                    }
                }
            }
        }
    }

    private fun Exception.toTransportError(): TransportError {
        val detail = message ?: this::class.simpleName ?: "Unknown error"
        return when {
            this::class.simpleName?.let { it.contains("SSL") || it.contains("TLS") || it.contains("Certificate") } == true ->
                TransportError.TlsError(detail, this)
            this::class.simpleName?.contains("Protocol") == true -> TransportError.ProtocolError(detail)
            else -> TransportError.IoError(detail, this)
        }
    }
}
