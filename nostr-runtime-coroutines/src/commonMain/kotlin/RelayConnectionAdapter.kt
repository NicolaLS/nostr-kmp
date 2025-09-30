package nostr.runtime.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult

internal class RelayConnectionAdapter(
    private val delegate: RelayConnection
) : RelayConnectionListener {

    val url: String = delegate.url

    private val openSignal = CompletableDeferred<Unit>()
    private val messages = Channel<String>(Channel.BUFFERED)

    @Volatile
    private var closure: RelayClosure? = null

    @Volatile
    private var failure: Throwable? = null

    private var started = false

    val incoming: Flow<String> = messages.receiveAsFlow()

    suspend fun open() {
        if (!started) {
            started = true
            try {
                delegate.connect(this)
            } catch (t: Throwable) {
                if (!openSignal.isCompleted) {
                    openSignal.completeExceptionally(t)
                }
            }
        }
        openSignal.await()
    }

    suspend fun send(frame: String) {
        when (val result = delegate.send(frame)) {
            RelaySendResult.Accepted -> return
            RelaySendResult.NotConnected -> throw IllegalStateException("Relay connection not open")
            is RelaySendResult.Failed -> throw result.cause
        }
    }

    suspend fun close(code: Int, reason: String?) {
        delegate.close(code, reason)
    }

    fun closeInfo(): RelayClosure? = closure

    fun failure(): Throwable? = failure

    fun dispose() {
        messages.close()
        if (!openSignal.isCompleted) {
            openSignal.cancel()
        }
    }

    override fun onOpen(connection: RelayConnection) {
        if (!openSignal.isCompleted) {
            openSignal.complete(Unit)
        }
    }

    override fun onMessage(message: String) {
        messages.trySend(message)
    }

    override fun onClosed(code: Int, reason: String?) {
        if (closure == null) {
            closure = RelayClosure(code, reason)
        }
        messages.close()
    }

    override fun onFailure(cause: Throwable) {
        failure = cause
        if (!openSignal.isCompleted) {
            openSignal.completeExceptionally(cause)
        }
        messages.close(cause)
    }
}

data class RelayClosure(val code: Int, val reason: String?)
