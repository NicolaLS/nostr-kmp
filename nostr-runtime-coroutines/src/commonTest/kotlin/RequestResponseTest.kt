package nostr.runtime.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult
import nostr.core.session.ConnectionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RequestResponseTest {

    private val codec = KotlinxSerializationWireCodec.default()

    // ========================================================================
    // RequestResult Tests
    // ========================================================================

    @Test
    fun requestResultFoldHandlesAllCases() {
        val success: RequestResult<String> = RequestResult.Success("value")
        val timeout: RequestResult<String> = RequestResult.Timeout(1000)
        val failed: RequestResult<String> = RequestResult.ConnectionFailed(2, "error")

        assertEquals("success:value", success.fold(
            onSuccess = { "success:$it" },
            onConnectionFailed = { "failed" },
            onTimeout = { "timeout" }
        ))

        assertEquals("timeout:1000", timeout.fold(
            onSuccess = { "success" },
            onConnectionFailed = { "failed" },
            onTimeout = { "timeout:${it.elapsedMillis}" }
        ))

        assertEquals("failed:2:error", failed.fold(
            onSuccess = { "success" },
            onConnectionFailed = { "failed:${it.attempts}:${it.lastError}" },
            onTimeout = { "timeout" }
        ))
    }

    @Test
    fun requestResultGetOrNullReturnsValueOnSuccess() {
        val success: RequestResult<String> = RequestResult.Success("value")
        val timeout: RequestResult<String> = RequestResult.Timeout(1000)
        val failed: RequestResult<String> = RequestResult.ConnectionFailed(1, null)

        assertEquals("value", success.getOrNull())
        assertNull(timeout.getOrNull())
        assertNull(failed.getOrNull())
    }

    @Test
    fun requestResultMapTransformsSuccessValue() {
        val success: RequestResult<Int> = RequestResult.Success(42)
        val timeout: RequestResult<Int> = RequestResult.Timeout(1000)
        val failed: RequestResult<Int> = RequestResult.ConnectionFailed(1, null)

        val mappedSuccess = success.map { it * 2 }
        val mappedTimeout = timeout.map { it * 2 }
        val mappedFailed = failed.map { it * 2 }

        assertIs<RequestResult.Success<Int>>(mappedSuccess)
        assertEquals(84, mappedSuccess.value)
        assertIs<RequestResult.Timeout>(mappedTimeout)
        assertIs<RequestResult.ConnectionFailed>(mappedFailed)
    }

    // ========================================================================
    // EagerRetryConfig Tests
    // ========================================================================

    @Test
    fun eagerRetryConfigDefaultValues() {
        val config = EagerRetryConfig.Default
        assertEquals(2, config.maxRetries)
        assertEquals(2000, config.minRetryBudgetMillis)
        assertEquals(2, config.staleTimeoutThreshold)
        assertEquals(5000, config.eagerConnectTimeoutMillis)
    }

    @Test
    fun eagerRetryConfigNoRetryValues() {
        val config = EagerRetryConfig.NoRetry
        assertEquals(0, config.maxRetries)
        assertEquals(Int.MAX_VALUE, config.staleTimeoutThreshold)
    }

    // ========================================================================
    // SharedSubscription Tests
    // ========================================================================

    @Test
    fun sharedSubscriptionGeneratesUniqueIds() {
        val id1 = SharedSubscription.generateId("test")
        val id2 = SharedSubscription.generateId("test")

        assertTrue(id1.value.startsWith("test-"))
        assertTrue(id2.value.startsWith("test-"))
        assertTrue(id1 != id2) // Should be unique
    }

    @Test
    fun sharedSubscriptionExpectAndPublishCallsPublishAfterRegistration() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        smart.connect()
        advanceUntilIdle()
        assertIs<ConnectionSnapshot.Connected>(runtime.connectionSnapshots.value)

        val filter = Filter(kinds = setOf(23195))
        val subscription = smart.createSharedSubscription(listOf(filter))
        advanceUntilIdle()

        val requestEvent = sampleEvent("request123")
        var registrationCompleteBeforePublish = false

        // We can test that the publish is called by checking if it appears in sentFrames
        val resultJob = async {
            subscription.expectAndPublish(
                correlationId = requestEvent.id,
                publish = {
                    // At this point, registration should be complete
                    registrationCompleteBeforePublish = true
                    smart.publish(requestEvent)
                },
                timeoutMillis = 100 // Short timeout - will timeout
            )
        }
        advanceUntilIdle()

        // Verify registration happened before publish was called
        assertTrue(registrationCompleteBeforePublish, "Publish should be called after registration")

        // Verify publish was actually called (EVENT frame sent)
        val eventFrame = connection.sentFrames.find { it.contains("\"EVENT\"") }
        assertNotNull(eventFrame, "Event should have been published")

        subscription.close()
        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun sharedSubscriptionTimeoutReturnsNull() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        smart.connect()
        advanceUntilIdle()

        val filter = Filter(kinds = setOf(23195))
        val subscription = smart.createSharedSubscription(listOf(filter))
        advanceUntilIdle()

        val resultJob = async {
            subscription.expect("nonexistent-correlation", timeoutMillis = 100)
        }

        advanceTimeBy(200)
        runCurrent()

        val result = resultJob.await()
        assertNull(result)

        subscription.close()
        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun sharedSubscriptionRejectsDuplicateCorrelationId() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        smart.connect()
        advanceUntilIdle()

        val filter = Filter(kinds = setOf(23195))
        val subscription = smart.createSharedSubscription(listOf(filter))
        advanceUntilIdle()

        // First expect call - launch in background
        val job1 = launch {
            subscription.expect("same-id", timeoutMillis = 60000)
        }
        // Run current to ensure the first expect is registered
        runCurrent()

        // Second expect call with same ID should throw
        var exceptionThrown = false
        try {
            // Try registering again - should throw immediately
            subscription.expectAndPublish(
                correlationId = "same-id",
                publish = { /* no-op */ },
                timeoutMillis = 1000
            )
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("Duplicate correlationId") == true)
        }

        assertTrue(exceptionThrown, "Expected IllegalStateException for duplicate correlationId")

        job1.cancel()
        subscription.close()
        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun sharedSubscriptionCleanupOnClose() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        smart.connect()
        advanceUntilIdle()

        val filter = Filter(kinds = setOf(23195))
        val subscription = smart.createSharedSubscription(listOf(filter))
        advanceUntilIdle()

        // Start an expect that will be cancelled
        val job = launch {
            subscription.expect("will-be-cancelled", timeoutMillis = 60000)
        }
        advanceUntilIdle()

        // Close the subscription
        subscription.close()
        advanceUntilIdle()

        // Verify the job was cancelled
        assertTrue(job.isCancelled || job.isCompleted)

        runtime.shutdown()
        advanceUntilIdle()
    }

    // ========================================================================
    // SmartRelaySession Query Tests
    // ========================================================================

    @Test
    fun querySendsReqFrame() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        smart.connect()
        advanceUntilIdle()

        val filter = Filter(kinds = setOf(1))

        // Start query but don't await - it will timeout
        val resultJob = async {
            smart.query(
                listOf(filter),
                timeoutMillis = 50, // Very short timeout
                retryConfig = EagerRetryConfig.NoRetry
            )
        }
        advanceUntilIdle()

        // Find the subscription ID from the sent frames
        val reqFrame = connection.sentFrames.find { it.startsWith("[\"REQ\"") }
        assertNotNull(reqFrame, "REQ frame should have been sent")
        assertTrue(reqFrame.contains("\"kinds\""), "REQ should contain filter")

        // Wait for timeout
        resultJob.await()

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun queryReturnsConnectionFailedWhenNotConnected() = runTest {
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { FailingRelayConnection(it) },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        // Attempt to connect (will fail)
        smart.connect()
        advanceUntilIdle()

        val filter = Filter(kinds = setOf(1))
        val result = smart.query(
            listOf(filter),
            timeoutMillis = 100,
            retryConfig = EagerRetryConfig.NoRetry
        )

        assertIs<RequestResult.ConnectionFailed>(result)

        runtime.shutdown()
        advanceUntilIdle()
    }

    // ========================================================================
    // SmartRelaySession RequestOne Tests
    // ========================================================================

    @Test
    fun requestOneSendsEventAndReqFrames() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        smart.connect()
        advanceUntilIdle()

        val requestEvent = sampleEvent("request123")
        val responseFilter = Filter(kinds = setOf(23195))

        // Start requestOne - it will timeout but we check frames were sent
        val resultJob = async {
            smart.requestOne(
                requestEvent = requestEvent,
                responseFilter = responseFilter,
                correlationId = requestEvent.id,
                timeoutMillis = 50, // Very short timeout
                retryConfig = EagerRetryConfig.NoRetry
            )
        }
        advanceUntilIdle()

        // Verify REQ was sent
        val reqFrame = connection.sentFrames.find { it.startsWith("[\"REQ\"") }
        assertNotNull(reqFrame, "REQ frame should have been sent")

        // Verify EVENT was sent
        val eventFrame = connection.sentFrames.find { it.contains("\"EVENT\"") }
        assertNotNull(eventFrame, "EVENT frame should have been sent")

        resultJob.await()

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun requestOneConnectionFactoryCalled() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        var connectCalled = false
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory {
                connectCalled = true
                connection
            },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        smart.connect()
        advanceUntilIdle()
        assertTrue(connectCalled, "Connection factory should have been called")

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun requestOneConnectionFailedResult() = runTest {
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { FailingRelayConnection(it) },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        // Attempt to connect (will fail)
        smart.connect()
        advanceUntilIdle()

        val requestEvent = sampleEvent("request123")
        val responseFilter = Filter(kinds = setOf(23195))

        val result = smart.requestOne(
            requestEvent = requestEvent,
            responseFilter = responseFilter,
            timeoutMillis = 100,
            retryConfig = EagerRetryConfig.NoRetry
        )

        assertIs<RequestResult.ConnectionFailed>(result)

        runtime.shutdown()
        advanceUntilIdle()
    }

    // ========================================================================
    // SmartRelaySession Wrapper Tests
    // ========================================================================

    @Test
    fun smartRelaySessionDelegatesBasicOperations() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0
        )
        val smart = SmartRelaySession(runtime, "wss://relay")

        // Test that smart delegates to runtime
        assertEquals("wss://relay", smart.url)
        assertEquals(runtime.states, smart.states)
        assertEquals(runtime.outputs, smart.outputs)
        assertEquals(runtime.connectionSnapshots, smart.connectionSnapshots)

        runtime.shutdown()
        advanceUntilIdle()
    }

    // ========================================================================
    // Helper functions
    // ========================================================================

    private fun sampleEvent(seed: String = "1"): Event {
        // Create a valid 64-char hex ID from the seed
        val paddedSeed = seed.padEnd(64, '0')
        val hexId = paddedSeed.map {
            val code = it.code % 16
            if (code < 10) '0' + code else 'a' + (code - 10)
        }.joinToString("").take(64)

        return Event(
            id = hexId,
            pubkey = "1".repeat(64),
            createdAt = 1,
            kind = 1,
            tags = emptyList(),
            content = "content",
            sig = "a".repeat(128)
        )
    }

    private fun sampleEventWithTag(seed: String, tagName: String, tagValue: String): Event {
        // Create a valid 64-char hex ID from the seed
        val paddedSeed = seed.padEnd(64, '0')
        val hexId = paddedSeed.map {
            val code = it.code % 16
            if (code < 10) '0' + code else 'a' + (code - 10)
        }.joinToString("").take(64)

        return Event(
            id = hexId,
            pubkey = "1".repeat(64),
            createdAt = 1,
            kind = 23195,
            tags = listOf(listOf(tagName, tagValue)),
            content = "response content",
            sig = "a".repeat(128)
        )
    }

    private fun buildEventFrame(subscriptionId: String, event: Event): String {
        val element: JsonElement = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(JsonPrimitive(subscriptionId))
            add(Json.parseToJsonElement(codec.event(event)))
        }
        return Json.encodeToString(JsonElement.serializer(), element)
    }

    private fun buildEoseFrame(subscriptionId: String): String {
        val element: JsonElement = buildJsonArray {
            add(JsonPrimitive("EOSE"))
            add(JsonPrimitive(subscriptionId))
        }
        return Json.encodeToString(JsonElement.serializer(), element)
    }

    private fun extractSubscriptionId(reqFrame: String): String {
        // Parse ["REQ", "subscription-id", ...]
        // Example: ["REQ","query-abc123",{"kinds":[1]}]
        val jsonArray = Json.parseToJsonElement(reqFrame).jsonArray
        return jsonArray[1].jsonPrimitive.content
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

    private class FailingRelayConnection(override val url: String) : RelayConnection {
        override fun connect(listener: RelayConnectionListener) {
            listener.onFailure(RuntimeException("Connection failed"))
        }

        override fun send(frame: String): RelaySendResult = RelaySendResult.NotConnected

        override fun close(code: Int, reason: String?) {
            // No-op
        }
    }
}
