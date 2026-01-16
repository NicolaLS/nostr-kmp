package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.RelayConnection
import io.github.nicolals.nostr.core.transport.RelayConnectionListener
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.github.nicolals.nostr.core.transport.TransportError
import io.github.nicolals.nostr.core.transport.TransportResult
import io.github.nicolals.nostr.core.transport.TransportState
import okio.ByteString

/**
 * Fake [RelayConnection] for testing.
 *
 * Allows tests to simulate transport events by calling methods like
 * [simulateOpen], [simulateMessage], etc.
 */
class FakeRelayConnection(
    override val url: RelayUrl,
) : RelayConnection {

    private var listener: RelayConnectionListener? = null

    private var _state: TransportState = TransportState.Idle
    override val state: TransportState
        get() = _state

    val sentMessages = mutableListOf<String>()
    var closeCode: Int? = null
    var closeReason: String? = null

    override fun connect() {
        check(_state == TransportState.Idle) { "Can only connect from Idle state" }
        _state = TransportState.Connecting
    }

    override fun send(text: String): TransportResult<Unit> {
        if (_state != TransportState.Open) {
            return TransportResult.failure(TransportError.InvalidState("send", _state))
        }
        sentMessages.add(text)
        return TransportResult.success()
    }

    override fun ping(payload: ByteString?): TransportResult<Unit> {
        if (_state != TransportState.Open) {
            return TransportResult.failure(TransportError.InvalidState("ping", _state))
        }
        return TransportResult.success()
    }

    override fun close(code: Int, reason: String?) {
        if (_state == TransportState.Closed || _state == TransportState.Closing) return
        closeCode = code
        closeReason = reason
        _state = TransportState.Closing
        listener?.onClosing(CloseInfo(code, reason, false))
        _state = TransportState.Closed
        listener?.onClosed(CloseInfo(code, reason, false))
    }

    override fun setListener(listener: RelayConnectionListener?) {
        this.listener = listener
    }

    // ===== Test Helpers =====

    fun simulateOpen() {
        _state = TransportState.Open
        listener?.onOpen()
    }

    fun simulateMessage(text: String) {
        listener?.onMessage(text)
    }

    fun simulatePong(payload: ByteString?) {
        listener?.onPong(payload)
    }

    fun simulateClosing(code: Int = CloseInfo.NORMAL_CLOSURE, reason: String? = null) {
        _state = TransportState.Closing
        listener?.onClosing(CloseInfo(code, reason, false))
    }

    fun simulateClosed(code: Int = CloseInfo.NORMAL_CLOSURE, reason: String? = null, remote: Boolean = false) {
        _state = TransportState.Closed
        listener?.onClosed(CloseInfo(code, reason, remote))
    }

    fun simulateFailure(error: TransportError) {
        _state = TransportState.Failed
        listener?.onFailure(error)
        // Note: actual implementations transition to Closed after Failed
        _state = TransportState.Closed
    }

    fun clearSentMessages() {
        sentMessages.clear()
    }
}

/**
 * Factory that creates [FakeRelayConnection] instances.
 *
 * Captures created connections for test inspection.
 */
class FakeRelayTransportFactory : RelayTransportFactory {

    val connections = mutableListOf<FakeRelayConnection>()

    var nextConnection: FakeRelayConnection? = null

    override fun create(url: RelayUrl, config: TransportConfig): RelayConnection {
        val connection = nextConnection ?: FakeRelayConnection(url)
        nextConnection = null
        connections.add(connection)
        return connection
    }

    val lastConnection: FakeRelayConnection?
        get() = connections.lastOrNull()

    fun reset() {
        connections.clear()
        nextConnection = null
    }
}
