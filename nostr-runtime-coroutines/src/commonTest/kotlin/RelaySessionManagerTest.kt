package nostr.runtime.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RelaySessionManagerTest {

    private val codec = KotlinxSerializationWireCodec.default()

    @Test
    fun acquireSharesRuntimeAndReleaseCreatesFreshInstance() = runTest {
        val connection = RecordingRelayConnection("wss://relay")
        val manager = RelaySessionManager(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec
        )

        val first = manager.acquire("wss://relay")
        val second = manager.acquire("wss://relay")
        assertSame(first.runtime, second.runtime)

        first.release()
        second.release()
        assertTrue(connection.closedInvocations == 0) // runtime never opened the connection

        val third = manager.acquire("wss://relay")
        assertNotSame(first.runtime, third.runtime)

        third.release()
        manager.shutdown()
    }

    @Test
    fun useReleasesSessionOnCompletion() = runTest {
        val connection = RecordingRelayConnection("wss://relay")
        val manager = RelaySessionManager(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0 // Disable idle timeout to simplify test
        )

        manager.use("wss://relay") {
            connect()
            advanceUntilIdle()
            disconnect()
            advanceUntilIdle()
        }
        advanceUntilIdle() // Allow release/shutdown processing
        assertTrue(connection.closeCalled, "Expected close to be called but it was not")

        val next = manager.acquire("wss://relay")
        next.release()
        manager.shutdown()
    }

    private class RecordingRelayConnection(override val url: String) : RelayConnection {
        private var listener: RelayConnectionListener? = null
        var closedInvocations: Int = 0
        var closeCalled: Boolean = false

        override fun connect(listener: RelayConnectionListener) {
            this.listener = listener
            listener.onOpen(this)
        }

        override fun send(frame: String): RelaySendResult = RelaySendResult.Accepted

        override fun close(code: Int, reason: String?) {
            closedInvocations += 1
            closeCalled = true
            listener?.onClosed(code, reason)
            listener = null
        }
    }
}
