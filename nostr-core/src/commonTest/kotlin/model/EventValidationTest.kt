package nostr.core.model

import nostr.core.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EventValidationTest {

    private val pubkey = "a".repeat(64)
    private val eventId = "b".repeat(64)
    private val sig = "c".repeat(128)

    @Test
    fun `event kind must be within protocol bounds`() {
        assertFailsWith<IllegalArgumentException> {
            Event(
                id = eventId,
                pubkey = pubkey,
                createdAt = 0,
                kind = Event.KIND_MAX + 1,
                tags = emptyList(),
                content = "",
                sig = sig
            )
        }
    }

    @Test
    fun `event id must be lowercase hex`() {
        assertFailsWith<IllegalArgumentException> {
            Event(
                id = "B".repeat(64),
                pubkey = pubkey,
                createdAt = 0,
                kind = 1,
                tags = emptyList(),
                content = "",
                sig = sig
            )
        }
    }

    @Test
    fun `unsigned event rejects uppercase pubkey`() {
        assertFailsWith<IllegalArgumentException> {
            UnsignedEvent(
                pubkey = "A".repeat(64),
                createdAt = 0,
                kind = 1,
                tags = emptyList(),
                content = ""
            )
        }
    }

    @Test
    fun `event tags must contain at least one value`() {
        assertFailsWith<IllegalArgumentException> {
            Event(
                id = eventId,
                pubkey = pubkey,
                createdAt = 0,
                kind = 1,
                tags = listOf(emptyList()),
                content = "",
                sig = sig
            )
        }
    }

    @Test
    fun `unsigned event tags must contain at least one value`() {
        assertFailsWith<IllegalArgumentException> {
            UnsignedEvent(
                pubkey = pubkey,
                createdAt = 0,
                kind = 1,
                tags = listOf(emptyList()),
                content = ""
            )
        }
    }

    @Test
    fun `event builder rejects empty tag`() {
        val builder = EventBuilder(clock = null, hasher = Sha256.Default)
        assertFailsWith<IllegalArgumentException> {
            builder.addTag(emptyList())
        }
    }

    @Test
    fun `event builder string overload rejects empty tag`() {
        val builder = EventBuilder(clock = null, hasher = Sha256.Default)
        assertFailsWith<IllegalArgumentException> {
            builder.addTag()
        }
    }
}
