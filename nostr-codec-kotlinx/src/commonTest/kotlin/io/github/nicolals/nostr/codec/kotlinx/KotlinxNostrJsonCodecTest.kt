package io.github.nicolals.nostr.codec.kotlinx

import io.github.nicolals.nostr.core.codec.JsonNumber
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.codec.wire.WireRelayEventMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KotlinxNostrJsonCodecTest {
    private val codec = KotlinxNostrJsonCodec()

    private val rawEventJson = """
        {
          "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "pubkey": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
          "created_at": 1712345678,
          "kind": 1,
          "tags": [
            ["p", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"],
            ["e", "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", "wss://relay.example.com"]
          ],
          "content": "hello",
          "sig": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        }
    """.trimIndent()

    private val expectedEvent = WireEvent(
        id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        pubkey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        createdAt = 1712345678L,
        kind = 1,
        tags = listOf(
            listOf(
                "p",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            ),
            listOf(
                "e",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "wss://relay.example.com",
            ),
        ),
        content = "hello",
        sig = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
    )

    private val rawEventJsonWithExponent = """
        {
          "id": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
          "pubkey": "1111111111111111111111111111111111111111111111111111111111111111",
          "created_at": 1e3,
          "kind": 1.0,
          "tags": [],
          "content": "",
          "sig": "2222222222222222222222222222222222222222222222222222222222222222"
        }
    """.trimIndent()

    private val expectedEventWithExponent = WireEvent(
        id = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        pubkey = "1111111111111111111111111111111111111111111111111111111111111111",
        createdAt = 1000L,
        kind = 1,
        tags = emptyList(),
        content = "",
        sig = "2222222222222222222222222222222222222222222222222222222222222222",
    )

    private val rawRelayEventMessageJson = """
        ["EVENT","sub-123",$rawEventJson]
    """.trimIndent()

    private val rawFilterJson = """
        {
          "ids": ["id-1", "id-2"],
          "authors": ["pub-1"],
          "kinds": [1, 2],
          "#e": ["event-1"],
          "#p": ["pub-2"],
          "since": 1712345678,
          "until": 1712349999,
          "limit": 42
        }
    """.trimIndent()

    private val expectedFilter = WireFilter(
        ids = listOf("id-1", "id-2"),
        authors = listOf("pub-1"),
        kinds = listOf(1L, 2L),
        tagFilters = mapOf(
            "e" to listOf("event-1"),
            "p" to listOf("pub-2"),
        ),
        since = 1712345678L,
        until = 1712349999L,
        limit = 42,
    )

    @Test
    fun rawEventDecodesToWireEvent() {
        val decoded = codec.decodeEvent(rawEventJson)
        assertEquals(expectedEvent, decoded)

        val encoded = codec.encodeEventString(expectedEvent)
        assertEquals(codec.parse(rawEventJson), codec.parse(encoded))
    }

    @Test
    fun rawRelayEventMessageDecodesToWireProtocolMessage() {
        val expectedMessage = WireRelayEventMessage(
            subscriptionId = "sub-123",
            event = expectedEvent,
        )
        val decoded = codec.decodeRelayMessage(rawRelayEventMessageJson)
        assertEquals(expectedMessage, decoded)

        val encoded = codec.encodeRelayMessageString(expectedMessage)
        assertEquals(codec.parse(rawRelayEventMessageJson), codec.parse(encoded))
    }

    @Test
    fun rawFilterDecodesToWireFilter() {
        val decoded = codec.decodeFilter(rawFilterJson)
        assertEquals(expectedFilter, decoded)

        val encoded = codec.encodeFilterString(expectedFilter)
        assertEquals(codec.parse(rawFilterJson), codec.parse(encoded))
    }

    @Test
    fun numericLiteralsAcceptExponentAndIntegralDecimals() {
        val decoded = codec.decodeEvent(rawEventJsonWithExponent)
        assertEquals(expectedEventWithExponent, decoded)
    }

    @Test
    fun stringifyRejectsInvalidJsonNumberLiteral() {
        assertFailsWith<IllegalArgumentException> {
            codec.stringify(JsonNumber("01"))
        }
    }
}
