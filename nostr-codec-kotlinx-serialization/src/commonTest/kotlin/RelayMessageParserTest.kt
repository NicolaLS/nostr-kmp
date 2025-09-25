package nostr.codec.kotlinx.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nostr.core.model.Event
import nostr.core.model.RelayMessage
import nostr.core.model.SubscriptionId
import nostr.core.model.UnsignedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayMessageParserTest {

    private val json = Json { encodeDefaults = false }
    private val codec = KotlinxSerializationWireCodec.default()

    @Test
    fun parsesEventFrame() {
        val unsigned = UnsignedEvent(
            pubkey = "1".repeat(64),
            createdAt = 1,
            kind = 1,
            tags = listOf(listOf("p", "peer")),
            content = "hi"
        )
        val event = Event.fromUnsigned(unsigned, "2".repeat(128))
        val frameElement: JsonElement = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(JsonPrimitive("sub"))
            add(json.parseToJsonElement(codec.event(event)))
        }
        val raw = json.encodeToString(JsonElement.serializer(), frameElement)
        val message = codec.relayMessage(raw)
        val parsed = assertIs<RelayMessage.Event>(message)
        assertEquals(SubscriptionId("sub"), parsed.subscriptionId)
        assertEquals(event, parsed.event)
    }

    @Test
    fun parsesEventWithBlankOptionalTagField() {
        val unsigned = UnsignedEvent(
            pubkey = "1".repeat(64),
            createdAt = 1,
            kind = 1,
            tags = listOf(listOf("e", "a".repeat(64), "", "root")),
            content = "hi"
        )
        val event = Event.fromUnsigned(unsigned, "2".repeat(128))
        val frameElement: JsonElement = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(JsonPrimitive("sub"))
            add(json.parseToJsonElement(codec.event(event)))
        }
        val raw = json.encodeToString(JsonElement.serializer(), frameElement)
        val message = codec.relayMessage(raw)
        val parsed = assertIs<RelayMessage.Event>(message)
        assertEquals(event, parsed.event)
    }

    @Test
    fun rejectsEventWithEmptyTagArray() {
        val eventObject = buildJsonObject {
            put("id", JsonPrimitive("a".repeat(64)))
            put("pubkey", JsonPrimitive("b".repeat(64)))
            put("created_at", JsonPrimitive(1))
            put("kind", JsonPrimitive(1))
            put("content", JsonPrimitive(""))
            put("sig", JsonPrimitive("c".repeat(128)))
            put("tags", buildJsonArray {
                add(buildJsonArray { })
            })
        }
        val frameElement: JsonElement = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(JsonPrimitive("sub"))
            add(eventObject)
        }
        val raw = json.encodeToString(JsonElement.serializer(), frameElement)
        val message = codec.relayMessage(raw)
        val unknown = assertIs<RelayMessage.Unknown>(message)
        assertTrue(unknown.reason.contains("Tag entry must contain at least one string"))
    }

    @Test
    fun rejectsEventWithInvalidSubscriptionId() {
        val eventJson = buildJsonObject {
            put("id", JsonPrimitive("a".repeat(64)))
            put("pubkey", JsonPrimitive("b".repeat(64)))
            put("created_at", JsonPrimitive(1))
            put("kind", JsonPrimitive(1))
            put("content", JsonPrimitive(""))
            put("sig", JsonPrimitive("c".repeat(128)))
            put("tags", buildJsonArray { add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive("peer")) }) })
        }
        val frameElement: JsonElement = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(JsonPrimitive(""))
            add(eventJson)
        }
        val raw = json.encodeToString(JsonElement.serializer(), frameElement)
        val message = codec.relayMessage(raw)
        val unknown = assertIs<RelayMessage.Unknown>(message)
        assertTrue(unknown.reason.contains("subscription id"))
    }

    @Test
    fun okFrameAllowsUppercaseIdAndCoercesMessage() {
        val frame = buildJsonArray {
            add(JsonPrimitive("OK"))
            add(JsonPrimitive("ABC"))
            add(JsonPrimitive(true))
            add(JsonPrimitive(0))
        }
        val raw = json.encodeToString(JsonElement.serializer(), frame)
        val message = codec.relayMessage(raw)
        val ok = assertIs<RelayMessage.Ok>(message)
        assertEquals("ABC", ok.result.eventId)
        assertEquals(null, ok.result.code)
        assertEquals("0", ok.result.message)
    }

    @Test
    fun closedFrameKeepsNonStringReasonAsNull() {
        val frame = buildJsonArray {
            add(JsonPrimitive("CLOSED"))
            add(JsonPrimitive("sub"))
            add(JsonPrimitive(1))
        }
        val raw = json.encodeToString(JsonElement.serializer(), frame)
        val message = codec.relayMessage(raw)
        val closed = assertIs<RelayMessage.Closed>(message)
        assertEquals(SubscriptionId("sub"), closed.subscriptionId)
        assertEquals(null, closed.code)
        assertEquals("1", closed.reason)
    }
}
