package nostr.runtime.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult

/**
 * Lightweight adapter that bridges callback-based [RelayConnection] to suspending coroutines.
 *
 * This adapter assumes single-threaded access; callers should confine instances to a
 * single coroutine context because state mutations are not synchronized.
 *
 * Timeout handling is the responsibility of the caller (typically [CoroutineNostrRuntime.raceHandshake]).
 */
internal class RelayConnectionAdapter(
    private val delegate: RelayConnection
) : RelayConnectionListener {

    val url: String = delegate.url

    private val openSignal = CompletableDeferred<Unit>()
    private val messages = Channel<String>(Channel.BUFFERED)

    private var closure: RelayClosure? = null

    private var failure: Throwable? = null

    private var started = false

    val incoming: Flow<String> = messages.receiveAsFlow()

    /**
     * Initiates the connection and suspends until [onOpen] is called or an error occurs.
     * Timeout handling should be done by the caller.
     */
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

    /**
     * Sends a frame and returns a [Deferred] that completes when the write is confirmed.
     *
     * The returned [Deferred] resolves to [WriteOutcome.Success] when the frame is actually
     * written to the socket, [WriteOutcome.Failed] if the write fails, or can be used with
     * a timeout to detect stale connections.
     *
     * @param frame the frame to send
     * @return a Deferred that completes when the write is confirmed (or fails)
     * @throws IllegalStateException if the connection is not open
     */
    fun send(frame: String): Deferred<WriteOutcome> {
        val confirmation = CompletableDeferred<WriteOutcome>()

        val result = delegate.sendWithConfirmation(frame) { success, cause ->
            if (success) {
                confirmation.complete(WriteOutcome.Success)
            } else {
                confirmation.complete(WriteOutcome.Failed(cause ?: IllegalStateException("Write failed")))
            }
        }

        // If the send was rejected synchronously, the callback was already invoked
        // but we should also throw for callers who don't check the Deferred
        when (result) {
            RelaySendResult.Accepted -> { /* Confirmation will come from callback */ }
            RelaySendResult.NotConnected -> {
                if (!confirmation.isCompleted) {
                    confirmation.complete(WriteOutcome.Failed(IllegalStateException("Relay connection not open")))
                }
            }
            is RelaySendResult.Failed -> {
                if (!confirmation.isCompleted) {
                    confirmation.complete(WriteOutcome.Failed(result.cause))
                }
            }
        }

        return confirmation
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
        val result = messages.trySend(message)
        if (result.isFailure) {
            val overflow = result.exceptionOrNull() ?: IllegalStateException("Inbound buffer overflow")
            failure = overflow
            messages.close(overflow)
        }
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
