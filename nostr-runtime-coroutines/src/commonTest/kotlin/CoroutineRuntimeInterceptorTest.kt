package nostr.runtime.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.RelayMessage
import nostr.core.model.SubscriptionId
import nostr.core.model.UnsignedEvent
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineRuntimeInterceptorTest {

    private val codec = KotlinxSerializationWireCodec.default()

    @Test
    fun interceptorReceivesLifecycleAndFrameCallbacks() = runTest {
        val connection = InspectingRelayConnection("wss://relay")
        val events = mutableListOf<String>()
        val interceptor = object : CoroutineRuntimeInterceptor {
            override suspend fun onConnectionOpening(url: String) {
                events += "opening:$url"
            }

            override suspend fun onConnectionEstablished(url: String) {
                events += "established:$url"
            }

            override suspend fun onConnectionClosed(url: String, code: Int?, reason: String?) {
                events += "closed:$url:$code"
            }

            override suspend fun onSend(url: String, message: ClientMessage) {
                events += "send:$url:${message::class.simpleName}"
            }

            override suspend fun onMessageReceived(url: String, message: RelayMessage) {
                events += "recv:$url:${message::class.simpleName}"
            }
        }

        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0,
            interceptors = listOf(interceptor)
        )

        runtime.connect("wss://relay")
        advanceUntilIdle()

        runtime.subscribe(SubscriptionId("sub"), listOf(Filter()))
        advanceUntilIdle()

        val event = sampleEvent()
        connection.emit(buildEventFrame("sub", event))
        advanceUntilIdle()

        runtime.disconnect()
        advanceUntilIdle()

        assertContains(events, "opening:wss://relay")
        assertContains(events, "established:wss://relay")
        assertTrue(events.any { it.startsWith("send:wss://relay") })
        assertTrue(events.any { it.startsWith("recv:wss://relay") })
        assertTrue(events.any { it.startsWith("closed:wss://relay") })

        runtime.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun interceptorFailureDoesNotInterruptRuntime() = runTest {
        val connection = InspectingRelayConnection("wss://relay")
        val events = mutableListOf<String>()
        val failing = object : CoroutineRuntimeInterceptor {
            override suspend fun onSend(url: String, message: nostr.core.model.ClientMessage) {
                error("boom")
            }
        }
        val capturing = object : CoroutineRuntimeInterceptor {
            override suspend fun onSend(url: String, message: nostr.core.model.ClientMessage) {
                events += "send:$url"
            }
        }

        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec,
            readTimeoutMillis = 0,
            interceptors = listOf(failing, capturing)
        )

        runtime.connect("wss://relay")
        advanceUntilIdle()

        runtime.subscribe(SubscriptionId("sub"), listOf(Filter()))
        advanceUntilIdle()

        val event = sampleEvent()
        runtime.publish(event)
        advanceUntilIdle()

        assertTrue(events.contains("send:wss://relay"))

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

    private class InspectingRelayConnection(override val url: String) : RelayConnection {
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

        fun emit(frame: String) {
            listener?.onMessage(frame)
        }
    }
}
