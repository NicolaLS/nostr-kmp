package nostr.codec.kotlinx.serialization

import nostr.core.codec.WireDecodingException
import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientMessageSerializationTest {

    private val codec = KotlinxSerializationWireCodec.default()

    @Test
    fun reqMessageRoundTrip() {
        val filters = listOf(Filter(authors = setOf("1".repeat(64))))
        val req = ClientMessage.Req(SubscriptionId("sub"), filters)
        val json = codec.clientMessage(req)
        val parsed = codec.clientMessage(json)
        assertEquals(req, parsed)
    }

    @Test
    fun eventMessageRoundTrip() {
        val event = Event(
            id = "1".repeat(64),
            pubkey = "2".repeat(64),
            createdAt = 1,
            kind = 1,
            tags = listOf(listOf("p", "peer")),
            content = "content",
            sig = "3".repeat(128)
        )
        val message = ClientMessage.Event(event)
        val json = codec.clientMessage(message)
        val parsed = codec.clientMessage(json)
        assertEquals(message, parsed)
    }

    @Test
    fun rejectsReqWithEmptySubscriptionId() {
        val json = "[\"REQ\", \"\"]"
        assertFailsWith<WireDecodingException> {
            codec.clientMessage(json)
        }
    }

    @Test
    fun rejectsCloseWithTooLongSubscriptionId() {
        val longId = "a".repeat(65)
        val json = "[\"CLOSE\", \"$longId\"]"
        assertFailsWith<WireDecodingException> {
            codec.clientMessage(json)
        }
    }
}
