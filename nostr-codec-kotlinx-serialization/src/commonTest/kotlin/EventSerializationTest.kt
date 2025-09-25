package nostr.codec.kotlinx.serialization

import kotlinx.serialization.json.Json
import nostr.core.crypto.Sha256
import nostr.core.model.Event
import nostr.core.model.UnsignedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventSerializationTest {

    private val json = Json { encodeDefaults = false }
    private val codec = KotlinxSerializationWireCodec.default()

    @Test
    fun unsignedEventIdMatchesRecomputedId() {
        val unsigned = UnsignedEvent(
            pubkey = "f".repeat(64),
            createdAt = 1_700_000_000,
            kind = 1,
            tags = listOf(
                listOf("p", "abc"),
                listOf("e", "def", "relay")
            ),
            content = "nostr is simple"
        )
        val id = unsigned.computeId(Sha256.Default)
        val signature = "a".repeat(128)
        val event = Event.fromUnsigned(unsigned, signature)

        assertEquals(id, event.id)
        assertTrue(event.verifyId())
    }

    @Test
    fun eventJsonRoundTrip() {
        val unsigned = UnsignedEvent(
            pubkey = "0".repeat(64),
            createdAt = 1_688_888_888,
            kind = 42,
            tags = listOf(listOf("client", "nostr-spike")),
            content = "content"
        )
        val event = Event.fromUnsigned(unsigned, signatureHex = "b".repeat(128))
        val encoded = codec.event(event)
        val decoded = codec.event(encoded)

        assertEquals(event, decoded)
    }
}
