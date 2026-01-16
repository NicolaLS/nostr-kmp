package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonBoolean
import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.core.codec.JsonNumber
import io.github.nicolals.nostr.core.codec.JsonObject
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.JsonValue
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import okio.ByteString

/**
 * Test fixtures for session tests.
 */
object TestFixtures {

    val testEventId = EventId.fromHex("a".repeat(64))
    val testEventId2 = EventId.fromHex("b".repeat(64))
    val testPubkey = PublicKey.fromHex("c".repeat(64))
    val testSig = EventSig.fromHex("d".repeat(128))

    val testEvent = Event(
        id = testEventId,
        pubkey = testPubkey,
        createdAt = UnixTimeSeconds(1700000000),
        kind = EventKind(1),
        tags = emptyList(),
        content = "test content",
        sig = testSig,
    )

    val testEvent2 = Event(
        id = testEventId2,
        pubkey = testPubkey,
        createdAt = UnixTimeSeconds(1700000001),
        kind = EventKind(1),
        tags = emptyList(),
        content = "test content 2",
        sig = testSig,
    )

    val testSubId = SubscriptionId("test-sub")
    val testSubId2 = SubscriptionId("test-sub-2")

    val testFilters = listOf(Filter(kinds = listOf(EventKind(1))))

    /**
     * Creates an EOSE message JSON string.
     */
    fun eoseJson(subId: SubscriptionId): String {
        return """["EOSE","${subId.value}"]"""
    }

    /**
     * Creates an OK message JSON string.
     */
    fun okJson(eventId: EventId, accepted: Boolean, message: String = ""): String {
        return """["OK","${eventId.hex}",$accepted,"$message"]"""
    }

    /**
     * Creates a CLOSED message JSON string.
     */
    fun closedJson(subId: SubscriptionId, reason: String = ""): String {
        return """["CLOSED","${subId.value}","$reason"]"""
    }

    /**
     * Creates an EVENT message JSON string.
     */
    fun eventJson(subId: SubscriptionId, event: Event): String {
        val tagsJson = event.tags.joinToString(",") { tag ->
            "[" + tag.joinToString(",") { "\"$it\"" } + "]"
        }
        return """["EVENT","${subId.value}",{"id":"${event.id.hex}","pubkey":"${event.pubkey.hex}","created_at":${event.createdAt.value},"kind":${event.kind.value},"tags":[$tagsJson],"content":"${event.content}","sig":"${event.sig.hex}"}]"""
    }

    /**
     * Creates a NOTICE message JSON string.
     */
    fun noticeJson(message: String): String {
        return """["NOTICE","$message"]"""
    }
}

/**
 * Simple JSON codec for tests.
 */
class TestJsonCodec : JsonCodec {

    override fun parse(json: String): JsonValue {
        val text = json
        return parseValue(text.trim())
    }

    override fun stringify(value: JsonValue): String {
        return when (value) {
            is JsonString -> "\"${value.value}\""
            is JsonNumber -> value.value
            is JsonBoolean -> value.value.toString()
            is JsonArray -> value.values.joinToString(",", "[", "]") { stringify(it) }
            is JsonObject -> value.entries.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":${stringify(v)}"
            }
            else -> "null"
        }
    }

    private fun parseValue(text: String): JsonValue {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("[") -> parseArray(trimmed)
            trimmed.startsWith("{") -> parseObject(trimmed)
            trimmed.startsWith("\"") -> JsonString(trimmed.removeSurrounding("\""))
            trimmed == "true" -> JsonBoolean(true)
            trimmed == "false" -> JsonBoolean(false)
            trimmed == "null" -> JsonString("null")
            else -> JsonNumber(trimmed)
        }
    }

    private fun parseArray(text: String): JsonArray {
        val inner = text.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return JsonArray(emptyList())

        val elements = mutableListOf<JsonValue>()
        var depth = 0
        var start = 0
        var inString = false
        var escape = false

        for (i in inner.indices) {
            val c = inner[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == '{' -> depth++
                !inString && c == ']' -> depth--
                !inString && c == '}' -> depth--
                !inString && c == ',' && depth == 0 -> {
                    elements.add(parseValue(inner.substring(start, i)))
                    start = i + 1
                }
            }
        }
        if (start < inner.length) {
            elements.add(parseValue(inner.substring(start)))
        }

        return JsonArray(elements)
    }

    private fun parseObject(text: String): JsonObject {
        val inner = text.removePrefix("{").removeSuffix("}").trim()
        if (inner.isEmpty()) return JsonObject(emptyMap())

        val entries = mutableMapOf<String, JsonValue>()
        var depth = 0
        var start = 0
        var inString = false
        var escape = false

        for (i in inner.indices) {
            val c = inner[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == '{' -> depth++
                !inString && c == ']' -> depth--
                !inString && c == '}' -> depth--
                !inString && c == ',' && depth == 0 -> {
                    parseEntry(inner.substring(start, i))?.let { (k, v) -> entries[k] = v }
                    start = i + 1
                }
            }
        }
        if (start < inner.length) {
            parseEntry(inner.substring(start))?.let { (k, v) -> entries[k] = v }
        }

        return JsonObject(entries)
    }

    private fun parseEntry(text: String): Pair<String, JsonValue>? {
        val colonIndex = text.indexOf(':')
        if (colonIndex < 0) return null
        val key = text.substring(0, colonIndex).trim().removeSurrounding("\"")
        val value = parseValue(text.substring(colonIndex + 1))
        return key to value
    }
}

/**
 * Wire codec that uses [TestJsonCodec] for parsing/encoding.
 */
class TestWireCodec : NostrWireCodec, JsonCodec by TestJsonCodec() {

    override fun decodeEvent(value: JsonValue): WireEvent {
        val obj = value as? JsonObject ?: error("Expected object")
        return WireEvent(
            id = (obj.entries["id"] as? JsonString)?.value ?: "",
            pubkey = (obj.entries["pubkey"] as? JsonString)?.value ?: "",
            createdAt = (obj.entries["created_at"] as? JsonNumber)?.value?.toLongOrNull() ?: 0,
            kind = (obj.entries["kind"] as? JsonNumber)?.value?.toIntOrNull() ?: 0,
            tags = (obj.entries["tags"] as? JsonArray)?.values?.map { tag ->
                (tag as? JsonArray)?.values?.map { (it as? JsonString)?.value ?: "" } ?: emptyList()
            } ?: emptyList(),
            content = (obj.entries["content"] as? JsonString)?.value ?: "",
            sig = (obj.entries["sig"] as? JsonString)?.value ?: "",
        )
    }

    override fun encodeEvent(event: WireEvent): JsonValue {
        return JsonObject(mapOf(
            "id" to JsonString(event.id),
            "pubkey" to JsonString(event.pubkey),
            "created_at" to JsonNumber(event.createdAt.toString()),
            "kind" to JsonNumber(event.kind.toString()),
            "tags" to JsonArray(event.tags.map { tag ->
                JsonArray(tag.map { JsonString(it) })
            }),
            "content" to JsonString(event.content),
            "sig" to JsonString(event.sig),
        ))
    }

    override fun decodeFilter(value: JsonValue): WireFilter {
        return WireFilter()
    }

    override fun encodeFilter(filter: WireFilter): JsonValue {
        val entries = mutableMapOf<String, JsonValue>()
        filter.ids?.let { entries["ids"] = JsonArray(it.map { s -> JsonString(s) }) }
        filter.authors?.let { entries["authors"] = JsonArray(it.map { s -> JsonString(s) }) }
        filter.kinds?.let { entries["kinds"] = JsonArray(it.map { k -> JsonNumber(k.toString()) }) }
        filter.since?.let { entries["since"] = JsonNumber(it.toString()) }
        filter.until?.let { entries["until"] = JsonNumber(it.toString()) }
        filter.limit?.let { entries["limit"] = JsonNumber(it.toString()) }
        return JsonObject(entries)
    }

    override fun decodeClientMessage(value: JsonValue): RawClientMessage {
        val arr = value as? JsonArray ?: error("Expected array")
        return RawClientMessage(arr)
    }

    override fun encodeClientMessage(message: ClientMessageView): JsonValue {
        // Just return the raw JSON array for all messages
        return message.raw.array
    }

    override fun decodeRelayMessage(value: JsonValue): RawRelayMessage {
        val arr = value as? JsonArray ?: error("Expected array")
        return RawRelayMessage(arr)
    }
}

/**
 * Stub crypto primitives for testing (not used in session tests).
 */
object StubCryptoPrimitives : CryptoPrimitives {
    private val notImplemented = CryptoError.Unsupported("test stub")

    override fun randomBytes(length: Int): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun ecdhXOnly(privateKey32: ByteString, publicKeyBytes: ByteString): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun sha256(data: ByteString): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun hmacSha256(key: ByteString, data: ByteString): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun hkdfExtractSha256(salt: ByteString, ikm: ByteString): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun hkdfExpandSha256(prk: ByteString, info: ByteString, length: Int): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun aes256CbcEncrypt(key32: ByteString, iv16: ByteString, plaintext: ByteString): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun aes256CbcDecrypt(key32: ByteString, iv16: ByteString, ciphertext: ByteString): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun chacha20IetfXor(key32: ByteString, nonce12: ByteString, input: ByteString): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun base64Encode(data: ByteString): CryptoResult<String> =
        CryptoResult.Err(notImplemented)

    override fun base64Decode(data: String): CryptoResult<ByteString> =
        CryptoResult.Err(notImplemented)

    override fun constantTimeEquals(a: ByteString, b: ByteString): CryptoResult<Boolean> =
        CryptoResult.Ok(a == b)
}

/**
 * Creates a test [NipModuleContext] with the test codec and stub crypto.
 */
fun createTestModuleContext(): NipModuleContext {
    return SimpleNipModuleContext(TestWireCodec(), StubCryptoPrimitives)
}
