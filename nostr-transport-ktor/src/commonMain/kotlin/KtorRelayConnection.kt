package nostr.transport.ktor

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult
import nostr.core.relay.WriteConfirmationCallback

/**
 * WebSocket-backed [RelayConnection] implementation using Ktor.
 *
 * This class expects single-threaded use; state changes are unsynchronized
 * and must remain confined to the supplied [scope].
 *
 * Supports true write confirmation via [sendWithConfirmation] - the callback is invoked
 * only after the frame is actually written to the WebSocket, enabling detection of
 * stale/dead connections.
 */
class KtorRelayConnection(
    override val url: String,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val frameWriteTimeoutMillis: Long = DEFAULT_FRAME_WRITE_TIMEOUT_MILLIS,
    private val pingIntervalMillis: Long = DEFAULT_PING_INTERVAL_MILLIS,
    private val pongTimeoutMillis: Long = DEFAULT_PONG_TIMEOUT_MILLIS
) : RelayConnection {

    /**
     * Queued frame with optional write confirmation callback.
     */
    private data class QueuedFrame(
        val frame: Frame,
        val onWritten: WriteConfirmationCallback?
    )

    private val outbound = Channel<QueuedFrame>(Channel.BUFFERED)
    private val pongSignals = Channel<Unit>(Channel.CONFLATED)

    private var connectJob: Job? = null
    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var heartbeatJob: Job? = null

    private var session: WebSocketSession? = null

    private var listener: RelayConnectionListener? = null

    private var closed: Boolean = false

    // Ensures terminal callbacks (onClosed/onFailure) are delivered exactly once
    private var terminalCallbackDelivered: Boolean = false

    override fun connect(listener: RelayConnectionListener) {
        check(this.listener == null) { "Connection already started" }
        require(frameWriteTimeoutMillis > 0) { "frameWriteTimeoutMillis must be positive" }
        require(pingIntervalMillis >= 0) { "pingIntervalMillis cannot be negative" }
        require(pongTimeoutMillis > 0) { "pongTimeoutMillis must be positive" }
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
            if (pingIntervalMillis > 0) {
                heartbeatJob = launchHeartbeat()
            }
        }
    }

    override fun send(frame: String): RelaySendResult {
        if (session == null || closed) {
            return RelaySendResult.NotConnected
        }
        val queued = QueuedFrame(Frame.Text(frame), onWritten = null)
        val result = outbound.trySend(queued)
        return when {
            result.isSuccess -> RelaySendResult.Accepted
            result.isClosed -> RelaySendResult.NotConnected
            else -> RelaySendResult.Failed(
                result.exceptionOrNull() ?: IllegalStateException("Outbound queue rejected frame")
            )
        }
    }

    override fun sendWithConfirmation(frame: String, onWritten: WriteConfirmationCallback): RelaySendResult {
        if (session == null || closed) {
            onWritten(false, IllegalStateException("Not connected"))
            return RelaySendResult.NotConnected
        }
        val queued = QueuedFrame(Frame.Text(frame), onWritten)
        val result = outbound.trySend(queued)
        return when {
            result.isSuccess -> RelaySendResult.Accepted
            result.isClosed -> {
                onWritten(false, IllegalStateException("Connection closed"))
                RelaySendResult.NotConnected
            }
            else -> {
                val cause = result.exceptionOrNull() ?: IllegalStateException("Outbound queue rejected frame")
                onWritten(false, cause)
                RelaySendResult.Failed(cause)
            }
        }
    }

    override fun close(code: Int, reason: String?) {
        if (closed) return
        closed = true
        closeOutbound()
        val current = session
        session = null
        scope.launch {
            runCatching { current?.close(CloseReason(code.toShort(), reason ?: "")) }
        }
        deliverClosed(code, reason)
    }

    private fun CoroutineScope.launchWriter(session: WebSocketSession) = launch {
        var inFlight: QueuedFrame? = null
        try {
            for (queued in outbound) {
                try {
                    inFlight = queued
                    sendFrameWithTimeout(session, queued.frame)
                    inFlight = null
                    queued.onWritten?.invoke(true, null)
                } catch (writeFailure: Throwable) {
                    inFlight = null
                    queued.onWritten?.invoke(false, writeFailure)
                    throw writeFailure  // Re-throw to fail the writer
                }
            }
        } catch (failure: Throwable) {
            val pending = inFlight
            if (pending != null) {
                pending.onWritten?.invoke(false, failure)
                inFlight = null
            }
            if (failure !is CancellationException) {
                deliverFailure(failure)
            }
        }
    }

    private suspend fun sendFrameWithTimeout(session: WebSocketSession, frame: Frame) {
        try {
            withTimeout(frameWriteTimeoutMillis) {
                session.send(frame)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw RelayWriteTimeoutException(url, frameWriteTimeoutMillis, timeout)
        }
    }

    private fun CoroutineScope.launchReader(session: WebSocketSession) = launch {
        try {
            for (frame in session.incoming) {
                // Capture listener reference for safe access
                val currentListener = listener ?: return@launch
                when (frame) {
                    is Frame.Text -> currentListener.onMessage(frame.readText())
                    is Frame.Pong -> pongSignals.trySend(Unit)
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
        }
    }

    private fun CoroutineScope.launchHeartbeat() = launch {
        try {
            while (true) {
                delay(pingIntervalMillis)
                if (session == null) return@launch
                drainPongs()
                val writeConfirmed = withTimeoutOrNull(frameWriteTimeoutMillis) {
                    val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val queued = QueuedFrame(Frame.Ping(ByteArray(0))) { success, cause ->
                        if (success) {
                            deferred.complete(Unit)
                        } else {
                            deferred.completeExceptionally(cause ?: IllegalStateException("Ping write failed"))
                        }
                    }
                    val result = outbound.trySend(queued)
                    if (!result.isSuccess) {
                        deferred.completeExceptionally(
                            result.exceptionOrNull() ?: IllegalStateException("Failed to enqueue ping")
                        )
                    }
                    deferred.await()
                }
                if (writeConfirmed == null) {
                    throw RelayWriteTimeoutException(url, frameWriteTimeoutMillis)
                }
                val pongReceived = withTimeoutOrNull(pongTimeoutMillis) { pongSignals.receive() } != null
                if (!pongReceived) {
                    throw RelayHeartbeatTimeoutException(url, pongTimeoutMillis)
                }
            }
        } catch (failure: Throwable) {
            if (failure !is CancellationException) {
                deliverFailure(failure)
            }
        }
    }

    private fun drainPongs() {
        while (pongSignals.tryReceive().getOrNull() != null) {
            // Drain stale pongs before waiting for a fresh one.
        }
    }

    /**
     * Delivers onClosed callback exactly once, then cleans up.
     */
    private fun deliverClosed(code: Int, reason: String?) {
        if (terminalCallbackDelivered) return
        terminalCallbackDelivered = true
        listener?.onClosed(code, reason)
        cleanup(IllegalStateException("Connection closed ($code)"))
    }

    /**
     * Delivers onFailure callback exactly once, then cleans up.
     */
    private fun deliverFailure(cause: Throwable) {
        if (terminalCallbackDelivered) return
        terminalCallbackDelivered = true
        listener?.onFailure(cause)
        cleanup(cause)
    }

    private fun closeOutbound() {
        outbound.close()
    }

    /**
     * Idempotent cleanup of internal resources.
     */
    private fun cleanup(cause: Throwable) {
        closeOutbound()
        failPendingWrites(cause)
        writerJob?.cancel()
        writerJob = null
        readerJob?.cancel()
        readerJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        val job = connectJob
        connectJob = null
        job?.cancel()
        val current = session
        session = null
        pongSignals.close()
        listener = null
        closed = true
        scope.launch {
            runCatching { current?.close(CloseReason(1000, "")) }
        }
    }

    private fun failPendingWrites(cause: Throwable) {
        while (true) {
            val queued = outbound.tryReceive().getOrNull() ?: break
            queued.onWritten?.invoke(false, cause)
        }
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
    client: HttpClient = HttpClient { install(WebSockets) },
    frameWriteTimeoutMillis: Long = DEFAULT_FRAME_WRITE_TIMEOUT_MILLIS,
    pingIntervalMillis: Long = DEFAULT_PING_INTERVAL_MILLIS,
    pongTimeoutMillis: Long = DEFAULT_PONG_TIMEOUT_MILLIS
): RelayConnectionFactory = RelayConnectionFactory { url ->
    KtorRelayConnection(
        url = url,
        client = client,
        scope = scope,
        frameWriteTimeoutMillis = frameWriteTimeoutMillis,
        pingIntervalMillis = pingIntervalMillis,
        pongTimeoutMillis = pongTimeoutMillis
    )
}

private const val DEFAULT_FRAME_WRITE_TIMEOUT_MILLIS: Long = 10_000
private const val DEFAULT_PING_INTERVAL_MILLIS: Long = 30_000
private const val DEFAULT_PONG_TIMEOUT_MILLIS: Long = 10_000

class RelayWriteTimeoutException(
    val relayUrl: String,
    val timeoutMillis: Long,
    cause: Throwable? = null
) : Exception("Write timed out after ${timeoutMillis}ms for $relayUrl", cause)

class RelayHeartbeatTimeoutException(
    val relayUrl: String,
    val timeoutMillis: Long
) : Exception("Heartbeat pong timeout after ${timeoutMillis}ms for $relayUrl")
