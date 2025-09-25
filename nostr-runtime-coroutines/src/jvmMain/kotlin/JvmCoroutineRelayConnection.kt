package nostr.runtime.coroutines

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocket.Listener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JvmCoroutineRelayConnection(
    override val url: String,
    private val client: HttpClient = HttpClient.newHttpClient()
) : CoroutineRelayConnection {

    private val socketRef = AtomicReference<WebSocket?>()
    private val incomingChannel = Channel<String>(capacity = Channel.BUFFERED)

    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    override suspend fun open() {
        suspendCancellableCoroutine<Unit> { continuation ->
            client.newWebSocketBuilder()
                .buildAsync(URI.create(url), object : Listener {
                    override fun onOpen(webSocket: WebSocket) {
                        socketRef.set(webSocket)
                        continuation.resume(Unit)
                        webSocket.request(1)
                    }

                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                        incomingChannel.trySend(data.toString())
                        webSocket.request(1)
                        return CompletableFuture.completedFuture(null)
                    }

                    override fun onError(webSocket: WebSocket, error: Throwable) {
                        socketRef.compareAndSet(webSocket, null)
                        incomingChannel.close(error)
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*> {
                        socketRef.compareAndSet(webSocket, null)
                        incomingChannel.close()
                        return CompletableFuture.completedFuture(null)
                    }
                })
                .whenComplete { ws, throwable ->
                    if (throwable != null && continuation.isActive) {
                        continuation.resumeWithException(throwable)
                    } else if (ws == null && continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("WebSocket creation returned null"))
                    }
                }

            continuation.invokeOnCancellation {
                socketRef.getAndSet(null)?.sendClose(WebSocket.NORMAL_CLOSURE, "cancelled")
                incomingChannel.close()
            }
        }
    }

    override suspend fun send(frame: String) {
        val socket = socketRef.get() ?: throw IllegalStateException("WebSocket not yet open")
        socket.sendText(frame, true).join()
    }

    override suspend fun close(code: Int, reason: String?) {
        socketRef.getAndSet(null)?.sendClose(code, reason ?: "")?.join()
        incomingChannel.close()
    }
}
