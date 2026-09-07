package io.github.nicolals.nostr.transport.ktor

import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.RelayConnectionListenerAdapter
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportError
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.github.nicolals.nostr.core.transport.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class KtorRelaySocketTest {
    private fun upgrade(peer: Socket) {
        peer.soTimeout = 5000
        val input = peer.getInputStream()
        val request = buildString {
            while (!endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0)
                append(byte.toChar())
            }
        }
        val key = request.lineSequence().first { it.startsWith("Sec-WebSocket-Key:", true) }
            .substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        )
        peer.getOutputStream().apply {
            write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
            flush()
        }
    }

    @Test fun unexpectedCoroutineCancellationStillNotifiesTerminalFailure() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val client = createDefaultHttpClient()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = KtorRelayConnection(
                client, RelayUrl.parse("ws://127.0.0.1:${server.localPort}"), TransportConfig(), owner,
            )
            val opened = CompletableDeferred<Unit>()
            val callbacks = mutableListOf<String>()
            connection.setListener(object : RelayConnectionListenerAdapter() {
                override fun onOpen() { opened.complete(Unit) }
                override fun onFailure(error: TransportError) { callbacks.add("failure") }
                override fun onClosed(info: CloseInfo) { callbacks.add("closed") }
            })
            val serve = async(Dispatchers.IO) {
                server.accept().use { peer ->
                    upgrade(peer)
                    while (peer.getInputStream().read() >= 0) { /* Wait for cancellation. */ }
                }
            }
            try {
                connection.connect()
                withTimeout(5000) { opened.await() }
                owner.cancel()
                withTimeout(5000) { connection.awaitClosed() }
                serve.await()
                assertEquals(TransportState.Closed, connection.state)
                assertEquals(listOf("failure", "closed"), callbacks)
            } finally {
                connection.close()
                client.close()
            }
        }
    }

    @Test fun framesRoundTripAndConfiguredSizeLimitRejectsOversizedMessages() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val factory = KtorRelayTransportFactory()
            val connection = factory.create(
                RelayUrl.parse("ws://127.0.0.1:${server.localPort}"), TransportConfig(maxFrameSizeBytes = 16),
            ) as KtorRelayConnection
            val received = mutableListOf<String>()
            val failure = CompletableDeferred<TransportError>()
            connection.setListener(object : RelayConnectionListenerAdapter() {
                override fun onOpen() { connection.send("outbound") }
                override fun onMessage(text: String) { received.add(text) }
                override fun onFailure(error: TransportError) { failure.complete(error) }
            })
            val exchange = async(Dispatchers.IO) {
                server.accept().use { peer ->
                    upgrade(peer)
                    val input = peer.getInputStream()
                    assertEquals(0x81, input.read())
                    val header = input.read()
                    assertTrue(header and 0x80 != 0)
                    val length = header and 0x7f
                    val mask = input.readNBytes(4)
                    val bytes = input.readNBytes(length)
                    val message = ByteArray(length) { index ->
                        (bytes[index].toInt() xor mask[index % 4].toInt()).toByte()
                    }.decodeToString()
                    assertEquals("outbound", message)
                    peer.getOutputStream().apply {
                        write(byteArrayOf(0x81.toByte(), 5) + "hello".toByteArray())
                        write(byteArrayOf(0x81.toByte(), 17) + ByteArray(17) { 'x'.code.toByte() })
                        flush()
                    }
                    while (input.read() >= 0) { /* Wait for client cleanup. */ }
                }
            }
            try {
                connection.connect()
                assertIs<TransportError.ProtocolError>(withTimeout(5000) { failure.await() })
                connection.awaitClosed()
                exchange.await()
                assertEquals(listOf("hello"), received)
            } finally {
                factory.closeAndJoin()
            }
        }
    }

    @Test fun closeDuringInflightHandshakeClosesPhysicalSocket() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val factory = KtorRelayTransportFactory()
            val connection = factory.create(RelayUrl.parse("ws://127.0.0.1:${server.localPort}")) as KtorRelayConnection
            val accepted = async(Dispatchers.IO) { server.accept() }
            try {
                connection.connect()
                withTimeout(5000) { accepted.await() }.use { peer ->
                    // Read the request before closing, so cancellation occurs while
                    // the engine is awaiting the server's upgrade response.
                    peer.soTimeout = 5000
                    withContext(Dispatchers.IO) {
                        val input = peer.getInputStream()
                        var tail = ""
                        while (!tail.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte != -1)
                            tail = (tail + byte.toChar()).takeLast(4)
                        }
                    }
                    connection.close()
                    withTimeout(5000) { connection.awaitClosed() }
                    val remaining = withContext(Dispatchers.IO) { peer.getInputStream().read() }
                    assertEquals(-1, remaining)
                    assertEquals(TransportState.Closed, connection.state)
                }
            } finally {
                factory.closeAndJoin()
            }
        }
    }

    @Test fun failedHandshakeEmitsFailureThenClosedExactlyOnceAfterCleanup() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val factory = KtorRelayTransportFactory()
            val connection = factory.create(RelayUrl.parse("ws://127.0.0.1:${server.localPort}")) as KtorRelayConnection
            val callbacks = mutableListOf<String>()
            val closed = CompletableDeferred<Unit>()
            connection.setListener(object : RelayConnectionListenerAdapter() {
                override fun onFailure(error: TransportError) { callbacks.add("failure") }
                override fun onClosed(info: CloseInfo) {
                    callbacks.add("closed")
                    assertEquals(TransportState.Closed, connection.state)
                    closed.complete(Unit)
                }
            })
            val reject = async(Dispatchers.IO) {
                server.accept().use { peer ->
                    peer.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
            try {
                connection.connect()
                withTimeout(5000) { closed.await() }
                connection.awaitClosed()
                reject.await()
                assertEquals(listOf("failure", "closed"), callbacks)
                assertTrue(connection.state.isTerminal)
            } finally {
                factory.closeAndJoin()
            }
        }
    }
}
