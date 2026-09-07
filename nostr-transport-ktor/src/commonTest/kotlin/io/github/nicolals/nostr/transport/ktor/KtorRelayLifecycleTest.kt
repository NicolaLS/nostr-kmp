package io.github.nicolals.nostr.transport.ktor

import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.RelayConnectionListenerAdapter
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.github.nicolals.nostr.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KtorRelayLifecycleTest {
    @Test fun closeCancelsConnectingBeforeHandshakeStarts() = runTest {
        val client = createDefaultHttpClient()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val connection = KtorRelayConnection(client, RelayUrl.parse("wss://unused.invalid"), TransportConfig(), scope)
        val callbacks = mutableListOf<String>()
        connection.setListener(object : RelayConnectionListenerAdapter() {
            override fun onOpen() { callbacks.add("open") }
            override fun onClosing(info: CloseInfo) { callbacks.add("closing") }
            override fun onClosed(info: CloseInfo) { callbacks.add("closed") }
        })
        try {
            connection.connect()
            connection.close()
            runCurrent()
            connection.awaitClosed()
            assertEquals(TransportState.Closed, connection.state)
            assertEquals(listOf("closing", "closed"), callbacks)
            assertTrue(scope.coroutineContext[Job]!!.isCancelled)
        } finally {
            client.close()
        }
    }

    @Test fun closingFactoryClosesIdleConnectionsButPreservesBorrowedClient() = runTest {
        val client = createDefaultHttpClient()
        try {
            val factory = KtorRelayTransportFactory(client)
            val connection = factory.create(RelayUrl.parse("wss://unused.invalid"))
            factory.closeAndJoin()
            assertEquals(TransportState.Closed, connection.state)
            assertTrue(client.coroutineContext[Job]!!.isActive)
            assertFailsWith<IllegalStateException> { factory.create(RelayUrl.parse("wss://unused.invalid")) }
            factory.closeAndJoin()
        } finally {
            client.close()
        }
    }

    @Test fun defaultFactoryCanReleaseOwnedClientAndIdleConnections() = runTest {
        val factory = KtorRelayTransportFactory()
        val connection = factory.create(RelayUrl.parse("wss://unused.invalid"))
        factory.closeAndJoin()
        assertEquals(TransportState.Closed, connection.state)
        factory.closeAndJoin()
    }
}
