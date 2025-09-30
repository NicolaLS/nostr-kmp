package nostr.runtime.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.model.UnsignedEvent
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.RelaySessionOutput
import nostr.core.relay.RelayConnection
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.RelayConnectionListener
import nostr.core.relay.RelaySendResult
import kotlin.test.Test
import kotlin.test.assertEquals
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
            wireDecoder = codec
        )

        runtime.connect("wss://relay")
        runtime.subscribe(SubscriptionId("sub"), listOf(Filter(kinds = setOf(1))))
        advanceUntilIdle()

        assertTrue(connection.sentFrames.any { it.startsWith("[\"REQ\"") })
        runtime.shutdown()
    }

    @Test
    fun incomingEventFlowsToOutputs() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec
        )

        runtime.connect("wss://relay")
        runtime.subscribe(SubscriptionId("sub"), listOf())
        advanceUntilIdle()

        val event = sampleEvent()
        val frame = buildEventFrame("sub", event)
        connection.emit(frame)

        val output =
            runtime.outputs.first { it is RelaySessionOutput.EventReceived } as RelaySessionOutput.EventReceived
        assertEquals(event, output.event)
        assertEquals(SubscriptionId("sub"), output.subscriptionId)

        runtime.shutdown()
    }

    @Test
    fun disconnectTransitionsToDisconnected() = runTest {
        val connection = FakeRelayConnection("wss://relay")
        val runtime = CoroutineNostrRuntime(
            scope = this,
            connectionFactory = RelayConnectionFactory { connection },
            wireEncoder = codec,
            wireDecoder = codec
        )

        runtime.connect("wss://relay")
        advanceUntilIdle()
        assertTrue(runtime.states.value.connection is ConnectionSnapshot.Connected)

        runtime.disconnect()
        advanceUntilIdle()

        assertTrue(runtime.states.value.connection is ConnectionSnapshot.Disconnected)

        runtime.shutdown()
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
}
