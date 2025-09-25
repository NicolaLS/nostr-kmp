package nostr.transport.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import nostr.runtime.coroutines.CoroutineRelayConnection
import nostr.runtime.coroutines.CoroutineRelayConnectionFactory

class KtorCoroutineRelayConnection(
    override val url: String,
    private val client: HttpClient,
    private val scope: CoroutineScope
) : CoroutineRelayConnection {

    private var session: WebSocketSession? = null
    private var readerJob: Job? = null
    private val incomingChannel = Channel<String>(Channel.BUFFERED)

    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    override suspend fun open() {
        if (session != null) return
        val created = client.webSocketSession(urlString = url)
        session = created
        readerJob = scope.launch {
            try {
                for (frame in created.incoming) {
                    if (frame is Frame.Text) {
                        incomingChannel.send(frame.readText())
                    }
                }
                incomingChannel.close()
            } catch (failure: Throwable) {
                incomingChannel.close(failure)
                throw failure
            }
        }
    }

    override suspend fun send(frame: String) {
        val session = session ?: throw IllegalStateException("WebSocket not open")
        session.send(Frame.Text(frame))
    }

    override suspend fun close(code: Int, reason: String?) {
        readerJob?.cancelAndJoin()
        readerJob = null
        val session = session ?: return
        this.session = null
        try {
            session.close(CloseReason(code.toShort(), reason ?: ""))
        } catch (_: Throwable) {
        }
        incomingChannel.close()
    }

}

fun KtorRelayConnectionFactory(
    scope: CoroutineScope,
    client: HttpClient = HttpClient { install(WebSockets) }
): CoroutineRelayConnectionFactory = CoroutineRelayConnectionFactory { url ->
    KtorCoroutineRelayConnection(url = url, client = client, scope = scope)
}
