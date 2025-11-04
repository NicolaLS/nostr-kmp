package io.github.nicolals.nostr.nips.nip42

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nostr.core.crypto.Signer
import nostr.core.crypto.event.EventId
import nostr.core.crypto.event.Signature
import nostr.core.crypto.keys.PublicKey
import nostr.core.model.Event
import nostr.core.time.Clock

class Nip42AuthTest {
    private val pubkeyHex = "a".repeat(64)
    private val clock = object : Clock {
        override fun nowSeconds(): Long = 1_700_000_000L
    }
    private val signer: Signer = object : Signer {
        override val publicKey: PublicKey = PublicKey.fromHex(pubkeyHex)
        override fun sign(eventId: EventId): Signature {
            val hex = eventId.toString()
            return Signature.fromHex(hex + hex)
        }
    }

    @Test
    fun buildAuthEventPopulatesMandatoryFields() {
        val event = Nip42Auth.buildAuthEvent(
            signer = signer,
            relayUrl = " wss://relay.example/ ",
            challenge = "abc123",
            clock = clock,
            content = "payload",
            additionalTags = listOf(listOf("client", "sdk"))
        )

        assertEquals(Nip42Auth.EVENT_KIND, event.kind)
        assertEquals(pubkeyHex, event.pubkey)
        assertEquals(clock.nowSeconds(), event.createdAt)
        assertEquals("payload", event.content)

        val relayTag = event.tags.firstOrNull { it.first() == Nip42Auth.TAG_RELAY }
        assertEquals(listOf(Nip42Auth.TAG_RELAY, "wss://relay.example/"), relayTag)

        val challengeTag = event.tags.firstOrNull { it.first() == Nip42Auth.TAG_CHALLENGE }
        assertEquals(listOf(Nip42Auth.TAG_CHALLENGE, "abc123"), challengeTag)

        assertTrue(event.tags.contains(listOf("client", "sdk")))
    }

    @Test
    fun unsignedBuilderLeavesUnsignedPayload() {
        val unsigned = Nip42Auth.buildUnsignedAuthEvent(
            pubkey = pubkeyHex,
            relayUrl = "wss://relay.example",
            challenge = "xyz",
            createdAt = 1700L,
            content = ""
        )
        assertEquals(Nip42Auth.EVENT_KIND, unsigned.kind)
        assertEquals(pubkeyHex, unsigned.pubkey)
        assertEquals(1700L, unsigned.createdAt)
        assertEquals("", unsigned.content)
        assertEquals(
            listOf(
                listOf(Nip42Auth.TAG_RELAY, "wss://relay.example"),
                listOf(Nip42Auth.TAG_CHALLENGE, "xyz")
            ),
            unsigned.tags.take(2)
        )
    }

    @Test
    fun helpersDetectAuthEventMetadata() {
        val event = signer.buildNip42AuthEvent(
            relayUrl = "wss://relay.example",
            challenge = "foobar",
            clock = clock
        )
        assertTrue(Nip42Auth.isAuthEvent(event))
        assertEquals("wss://relay.example", Nip42Auth.relayUrl(event))
        assertEquals("foobar", Nip42Auth.challenge(event))
    }

    @Test
    fun helperReturnNullWhenTagMissing() {
        val unrelated = Event(
            id = "1".repeat(64),
            pubkey = pubkeyHex,
            createdAt = 1L,
            kind = 1,
            tags = listOf(listOf("p", "value")),
            content = "",
            sig = "2".repeat(128)
        )
        assertFalse(Nip42Auth.isAuthEvent(unrelated))
        assertNull(Nip42Auth.relayUrl(unrelated))
        assertNull(Nip42Auth.challenge(unrelated))
    }

    @Test
    fun additionalTagsMustContainValues() {
        val emptyTag = listOf<String>()
        assertFailsWithMessage("additional tag entries must contain at least one value") {
            Nip42Auth.buildUnsignedAuthEvent(
                pubkey = pubkeyHex,
                relayUrl = "wss://relay.example",
                challenge = "challenge",
                createdAt = 1,
                additionalTags = listOf(emptyTag)
            )
        }
    }

    @Test
    fun challengeMustNotBeBlank() {
        assertFailsWithMessage("challenge must not be blank") {
            Nip42Auth.buildUnsignedAuthEvent(
                pubkey = pubkeyHex,
                relayUrl = "wss://relay.example",
                challenge = "   ",
                createdAt = 1
            )
        }
    }

    @Test
    fun relayMustNotBeBlankAfterNormalization() {
        assertFailsWithMessage("relayUrl must not be blank") {
            Nip42Auth.buildUnsignedAuthEvent(
                pubkey = pubkeyHex,
                relayUrl = "   ",
                challenge = "challenge",
                createdAt = 1,
                normalizer = { it.trim() }
            )
        }
    }

    private fun assertFailsWithMessage(message: String, block: () -> Unit) {
        runCatching { block() }.onSuccess {
            throw AssertionError("Expected failure with message '$message'")
        }.onFailure { failure ->
            val actual = failure.message
            assertNotNull(actual)
            assertTrue(actual.contains(message), "Expected message to contain '$message' but was '$actual'")
        }
    }
}
