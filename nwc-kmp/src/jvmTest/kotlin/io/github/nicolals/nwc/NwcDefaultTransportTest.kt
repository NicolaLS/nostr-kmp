package io.github.nicolals.nwc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class NwcDefaultTransportTest {
    @Test
    fun defaultClientCompletesARealWebSocketHandshake() = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            var accepted: Socket? = null
            val serverThread = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        accepted = socket
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val request = buildString {
                            while (!endsWith("\r\n\r\n")) {
                                val next = input.read()
                                check(next >= 0)
                                append(next.toChar())
                            }
                        }
                        val key = request.lineSequence().first {
                            it.startsWith("Sec-WebSocket-Key:", ignoreCase = true)
                        }.substringAfter(':').trim()
                        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                            flush()
                        }
                        while (input.read() >= 0) { /* Keep the socket open until the client closes. */ }
                    }
                }
            }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val uri = NwcConnectionUri.parse(
                "nostr+walletconnect://79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798" +
                    "?relay=ws://127.0.0.1:${server.localPort}&secret=${"2".padStart(64, '0')}"
            )!!
            val client = NwcClient(uri, scope, cachedWalletInfo = WalletInfo.default())
            try {
                assertTrue(client.awaitReady(4_000), "Default client did not connect: ${client.state.value}")
            } finally {
                client.close()
                scope.cancel()
                accepted?.close()
                serverThread.join(1_000)
            }
        }
    }
}
