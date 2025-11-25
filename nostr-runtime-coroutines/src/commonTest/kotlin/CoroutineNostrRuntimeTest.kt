package nostr.runtime.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.coroutines.withTimeout
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.model.UnsignedEvent
import nostr.core.session.ConnectionFailureReason
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.RelaySessionOutput
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineNostrRuntimeTest {

    private val codec = KotlinxSerializationWireCodec.default()

    @Test
    fun subscribeSendsReqWhenConnected() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )

        runtime.connect("wss://relay")
        runtime.subscribe(SubscriptionId("sub"), listOf(Filter(kinds = setOf(1))))
        advanceUntilIdle()

        assertTrue(connection.sentFrames.any { it.startsWith("[\"REQ\"") })
        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun incomingEventFlowsToOutputs() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )

        runtime.connect("wss://relay")
        runtime.subscribe(SubscriptionId("sub"), listOf())
        advanceUntilIdle()
        assertIs<ConnectionSnapshot.Connected>(runtime.connectionSnapshots.value)

        val outputDeferred = async {
            withTimeout(5_000) {
                runtime.outputs.first { it is RelaySessionOutput.EventReceived }
            } as RelaySessionOutput.EventReceived
        }

        val event = sampleEvent()
        val frame = buildEventFrame("sub", event)
        checkNotNull(connection.listener)
        connection.emit(frame)
        advanceUntilIdle()

        val output = outputDeferred.await()
        assertEquals(event, output.event)
        assertEquals(SubscriptionId("sub"), output.subscriptionId)

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun disconnectTransitionsToDisconnected() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )

        runtime.connect("wss://relay")
        advanceUntilIdle()
        assertTrue(runtime.states.value.connection is ConnectionSnapshot.Connected)
        assertTrue(runtime.connectionSnapshots.value is ConnectionSnapshot.Connected)

        runtime.disconnect()
        advanceUntilIdle()

        assertTrue(runtime.states.value.connection is ConnectionSnapshot.Disconnected)
        assertTrue(runtime.connectionSnapshots.value is ConnectionSnapshot.Disconnected)

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun telemetryTracksFailuresAndAttempts() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        var attempts = 0
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory {
                attempts += 1
                if (attempts == 1) {
                    error("boom")
                }
                connection
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )

        runtime.connect("wss://relay")
        advanceUntilIdle()

        val failed = runtime.connectionTelemetry.value
        val failureSnapshot = failed.snapshot
        val failedSnapshot = assertIs<ConnectionSnapshot.Failed>(failureSnapshot)
        assertEquals(ConnectionFailureReason.ConnectionFactory, failedSnapshot.reason)
        assertEquals("boom", failedSnapshot.message)
        assertTrue(failedSnapshot.cause?.endsWith("IllegalStateException") == true)
        assertEquals(1, failed.attempt)
        val lastFailure = failed.lastFailure
        assertNotNull(lastFailure)
        assertEquals(ConnectionFailureReason.ConnectionFactory, lastFailure.reason)
        assertEquals("wss://relay", lastFailure.url)
        assertEquals(1, lastFailure.attempt)
        assertTrue(lastFailure.cause?.endsWith("IllegalStateException") == true)
        assertTrue(failed.isRetrying.not())

        runtime.connect("wss://relay")
        advanceUntilIdle()

        val recovered = runtime.connectionTelemetry.value
        val recoveredSnapshot = assertIs<ConnectionSnapshot.Connected>(recovered.snapshot)
        assertEquals(2, recovered.attempt)
        assertTrue(recovered.isRetrying)
        assertNotNull(recovered.lastFailure)

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun connectTimeoutFailsStuckHandshake() = runTest {
        val connection = HangingRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 0
        )

        runtime.connect("wss://relay")
        advanceTimeBy(1_100)
        advanceUntilIdle()

        val failedSnapshot = assertIs<ConnectionSnapshot.Failed>(runtime.connectionSnapshots.value)
        assertEquals(ConnectionFailureReason.OpenHandshake, failedSnapshot.reason)
        assertTrue(failedSnapshot.message.lowercase().contains("timeout"))

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun idleTimeoutFailsSilentConnection() = runTest {
        val connection = SilentRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000
        )

        runtime.connect("wss://relay")
        runCurrent()
        assertIs<ConnectionSnapshot.Connected>(runtime.connectionSnapshots.value)

        advanceTimeBy(1_200)
        advanceUntilIdle()
        val failedSnapshot = withTimeout(2_000) {
            runtime.connectionSnapshots.first { it is ConnectionSnapshot.Failed }
        } as ConnectionSnapshot.Failed
        assertEquals(ConnectionFailureReason.StreamFailure, failedSnapshot.reason)
        assertTrue(failedSnapshot.message.lowercase().contains("timeout") || failedSnapshot.message.lowercase()
            .contains("idle"))

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun manualReconnectToSameUrlAfterFailure() = runTest {
        // This test verifies the fix for the "stuck in Connecting" bug.
        // Previously, reconnecting to the same URL after failure would return early
        // because the stale connection reference still had the same URL.
        var connectionAttempts = 0
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { url ->
                connectionAttempts++
                if (connectionAttempts == 1) {
                    FailingRelayConnection(url)
                } else {
                    FakeRelayConnection(url)
                }
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )

        // First connection attempt fails
        runtime.connect("wss://relay")
        advanceUntilIdle()
        assertIs<ConnectionSnapshot.Failed>(runtime.connectionSnapshots.value)
        assertEquals(1, connectionAttempts)

        // Manual reconnect to the same URL should work
        runtime.connect("wss://relay")
        advanceUntilIdle()
        assertIs<ConnectionSnapshot.Connected>(runtime.connectionSnapshots.value)
        assertEquals(2, connectionAttempts)

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun autoReconnectWithExponentialBackoff() = runTest {
        var connectionAttempts = 0
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { url ->
                connectionAttempts++
                if (connectionAttempts <= 2) {
                    FailingRelayConnection(url)
                } else {
                    FakeRelayConnection(url)
                }
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0,
            reconnectionPolicy = ExponentialBackoffPolicy(
                baseDelayMillis = 1_000,
                maxDelayMillis = 10_000,
                maxAttempts = 5,
                jitterFactor = 0.0 // No jitter for deterministic testing
            )
        )

        // First connection attempt fails
        runtime.connect("wss://relay")
        runCurrent() // Process only ready tasks, don't advance time
        assertIs<ConnectionSnapshot.Failed>(runtime.connectionSnapshots.value)
        assertEquals(1, connectionAttempts)

        // After 1 second, first retry (1s base delay)
        advanceTimeBy(1_100)
        runCurrent()
        assertIs<ConnectionSnapshot.Failed>(runtime.connectionSnapshots.value)
        assertEquals(2, connectionAttempts)

        // After 2 more seconds, second retry (2s delay after first retry)
        advanceTimeBy(2_100)
        runCurrent()
        assertIs<ConnectionSnapshot.Connected>(runtime.connectionSnapshots.value)
        assertEquals(3, connectionAttempts)

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun noReconnectWithNoReconnectionPolicy() = runTest {
        var connectionAttempts = 0
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { url ->
                connectionAttempts++
                FailingRelayConnection(url)
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0,
            reconnectionPolicy = NoReconnectionPolicy
        )

        runtime.connect("wss://relay")
        advanceUntilIdle()
        assertIs<ConnectionSnapshot.Failed>(runtime.connectionSnapshots.value)
        assertEquals(1, connectionAttempts)

        // Advance time significantly - no reconnection should happen
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(1, connectionAttempts) // Still only 1 attempt

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun reconnectCancelledOnUserDisconnect() = runTest {
        var connectionAttempts = 0
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { url ->
                connectionAttempts++
                FailingRelayConnection(url)
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0,
            reconnectionPolicy = ExponentialBackoffPolicy(
                baseDelayMillis = 5_000,
                jitterFactor = 0.0
            )
        )

        runtime.connect("wss://relay")
        runCurrent() // Process only ready tasks
        assertIs<ConnectionSnapshot.Failed>(runtime.connectionSnapshots.value)
        assertEquals(1, connectionAttempts)

        // User disconnects before reconnection timer fires (5s backoff)
        advanceTimeBy(2_000) // Not enough for 5s backoff
        runCurrent()
        runtime.disconnect()
        runCurrent()

        // Wait for what would have been the reconnection time
        advanceTimeBy(10_000)
        runCurrent()

        // No reconnection should have happened
        assertEquals(1, connectionAttempts)
        assertIs<ConnectionSnapshot.Disconnected>(runtime.connectionSnapshots.value)

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun reconnectAfterConnectionDropsDuringSession() = runTest {
        var connectionAttempts = 0
        lateinit var activeConnection: FailableRelayConnection
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { url ->
                connectionAttempts++
                FailableRelayConnection(url).also { activeConnection = it }
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0,
            reconnectionPolicy = ExponentialBackoffPolicy(
                baseDelayMillis = 1_000,
                jitterFactor = 0.0
            )
        )

        runtime.connect("wss://relay")
        runCurrent()
        assertIs<ConnectionSnapshot.Connected>(runtime.connectionSnapshots.value)
        assertEquals(1, connectionAttempts)

        // Simulate network failure during active session
        activeConnection.simulateFailure(RuntimeException("Network lost"))
        runCurrent()
        assertIs<ConnectionSnapshot.Failed>(runtime.connectionSnapshots.value)

        // After backoff delay, should reconnect
        advanceTimeBy(1_100)
        runCurrent()
        assertIs<ConnectionSnapshot.Connected>(runtime.connectionSnapshots.value)
        assertEquals(2, connectionAttempts)

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun maxRetriesRespected() = runTest {
        var connectionAttempts = 0
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { url ->
                connectionAttempts++
                FailingRelayConnection(url)
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0,
            reconnectionPolicy = ExponentialBackoffPolicy(
                baseDelayMillis = 100,
                maxAttempts = 3,
                jitterFactor = 0.0
            )
        )

        runtime.connect("wss://relay")
        runCurrent()
        assertEquals(1, connectionAttempts)

        // First retry after 100ms
        advanceTimeBy(150)
        runCurrent()
        assertEquals(2, connectionAttempts)

        // Second retry after 200ms
        advanceTimeBy(250)
        runCurrent()
        assertEquals(3, connectionAttempts)

        // No more retries (maxAttempts = 3)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, connectionAttempts) // Still 3, no more retries

        runtime.shutdown()
        advanceUntilIdle()
    }

    private fun sampleEvent(): Event {
        val unsigned = UnsignedEvent(
            pubkey = "1".repeat(64),
            createdAt = 1,
            kind = 1,
            tags = listOf(listOf("p", "peer")),
            content = "content"
        )
        return Event.fromUnsigned(unsigned, "a".repeat(128))
    }

    private fun buildEventFrame(subscriptionId: String, event: Event): String {
        val element: JsonElement = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(JsonPrimitive(subscriptionId))
            add(Json.parseToJsonElement(codec.event(event)))
        }
        return Json.encodeToString(JsonElement.serializer(), element)
    }

    private class FakeRelayConnection(override val url: String) : RelayConnection {
        var listener: RelayConnectionListener? = null
        val sentFrames = mutableListOf<String>()

        override fun connect(listener: RelayConnectionListener) {
            this.listener = listener
            listener.onOpen(this)
        }

        override fun send(frame: String): RelaySendResult {
            sentFrames += frame
            return RelaySendResult.Accepted
        }

        override fun close(code: Int, reason: String?) {
            listener?.onClosed(code, reason)
            listener = null
        }

        fun emit(frame: String) {
            listener?.onMessage(frame)
        }
    }

    private class HangingRelayConnection(override val url: String) : RelayConnection {
        private var listener: RelayConnectionListener? = null

        override fun connect(listener: RelayConnectionListener) {
            this.listener = listener
            // Intentionally never call onOpen/onFailure to simulate a stalled handshake
        }

        override fun send(frame: String): RelaySendResult = RelaySendResult.NotConnected

        override fun close(code: Int, reason: String?) {
            listener?.onClosed(code, reason)
            listener = null
        }
    }

    private class SilentRelayConnection(override val url: String) : RelayConnection {
        private var listener: RelayConnectionListener? = null

        override fun connect(listener: RelayConnectionListener) {
            this.listener = listener
            listener.onOpen(this)
            // No frames will be emitted; connection never closes on its own.
        }

        override fun send(frame: String): RelaySendResult = RelaySendResult.Accepted

        override fun close(code: Int, reason: String?) {
            listener?.onClosed(code, reason)
            listener = null
        }
    }

    /** Connection that immediately fails on connect. */
    private class FailingRelayConnection(override val url: String) : RelayConnection {
        override fun connect(listener: RelayConnectionListener) {
            listener.onFailure(RuntimeException("Connection failed"))
        }

        override fun send(frame: String): RelaySendResult = RelaySendResult.NotConnected

        override fun close(code: Int, reason: String?) {
            // No-op
        }
    }

    /** Connection that can be triggered to fail after successful connection. */
    private class FailableRelayConnection(override val url: String) : RelayConnection {
        private var listener: RelayConnectionListener? = null
        val sentFrames = mutableListOf<String>()

        override fun connect(listener: RelayConnectionListener) {
            this.listener = listener
            listener.onOpen(this)
        }

        override fun send(frame: String): RelaySendResult {
            sentFrames += frame
            return RelaySendResult.Accepted
        }

        override fun close(code: Int, reason: String?) {
            listener?.onClosed(code, reason)
            listener = null
        }

        fun simulateFailure(cause: Throwable) {
            listener?.onFailure(cause)
            listener = null
        }

        fun emit(frame: String) {
            listener?.onMessage(frame)
        }
    }
}
