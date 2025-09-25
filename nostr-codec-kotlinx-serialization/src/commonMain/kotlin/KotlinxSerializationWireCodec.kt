package nostr.codec.kotlinx.serialization

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nostr.core.codec.WireDecoder
import nostr.core.codec.WireDecodingException
import nostr.core.codec.WireEncoder
import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.PublishResult
import nostr.core.model.RelayMessage
import nostr.core.model.SubscriptionId
import nostr.core.utils.isLowercaseHex

private val defaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    prettyPrint = false
}

class KotlinxSerializationWireCodec(
    private val json: Json = defaultJson
) : WireEncoder, WireDecoder {

    companion object {
        fun default(json: Json = defaultJson): KotlinxSerializationWireCodec = KotlinxSerializationWireCodec(json)
    }

    override fun clientMessage(message: ClientMessage): String {
        val element = when (message) {
            is ClientMessage.Event -> buildJsonArray {
                add(JsonPrimitive("EVENT"))
                add(eventToJson(message.event))
            }

            is ClientMessage.Req -> buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(message.subscriptionId.value))
                message.filters.forEach { add(filterToJson(it)) }
            }

            is ClientMessage.Close -> buildJsonArray {
                add(JsonPrimitive("CLOSE"))
                add(JsonPrimitive(message.subscriptionId.value))
            }

            is ClientMessage.Auth -> buildJsonArray {
                add(JsonPrimitive("AUTH"))
                add(eventToJson(message.event))
            }
        }
        return json.encodeToString(JsonElement.serializer(), element)
    }

    override fun event(event: Event): String =
        json.encodeToString(JsonElement.serializer(), eventToJson(event))

    override fun filter(filter: Filter): String =
        json.encodeToString(JsonElement.serializer(), filterToJson(filter))

    override fun relayMessage(raw: String): RelayMessage {
        return try {
            val element = json.parseToJsonElement(raw)
            val array = element as? JsonArray
                ?: return RelayMessage.Unknown(raw, "Relay frame must be a JSON array")
            if (array.isEmpty()) {
                return RelayMessage.Unknown(raw, "Relay frame cannot be empty")
            }
            when (val token = array[0].jsonPrimitive.content.uppercase()) {
                "EVENT" -> parseRelayEvent(array, raw)
                "NOTICE" -> parseNotice(array, raw)
                "EOSE" -> parseEose(array, raw)
                "OK" -> parseOk(array, raw)
                "CLOSED" -> parseClosed(array, raw)
                "AUTH" -> parseAuth(array, raw)
                "COUNT" -> parseCount(array, raw)
                else -> RelayMessage.Unknown(raw, "Unsupported relay message type '$token'", null)
            }
        } catch (failure: Throwable) {
            RelayMessage.Unknown(
                raw = raw,
                reason = failure.message ?: failure::class.simpleName.orEmpty(),
                cause = failure
            )
        }
    }

    override fun clientMessage(raw: String): ClientMessage {
        try {
            val array = json.parseToJsonElement(raw).jsonArray
            if (array.isEmpty()) throw WireDecodingException("Client message array cannot be empty")
            val kind = array[0].jsonPrimitive.content
            return when (kind.uppercase()) {
                "EVENT" -> ClientMessage.Event(parseEvent(array.getOrThrow(1)))
                "REQ" -> {
                    val subscriptionRaw = array.getOrThrow(1).jsonPrimitive.content
                    val parsedId = subscriptionRaw.toSubscriptionIdOrThrow()
                    val filters = array.drop(2).map { parseFilter(it) }
                    ClientMessage.Req(parsedId, filters)
                }

                "CLOSE" -> {
                    val subscriptionRaw = array.getOrThrow(1).jsonPrimitive.content
                    val parsedId = subscriptionRaw.toSubscriptionIdOrThrow()
                    ClientMessage.Close(parsedId)
                }
                "AUTH" -> ClientMessage.Auth(parseEvent(array.getOrThrow(1)))
                else -> throw WireDecodingException("Unsupported client message type: $kind")
            }
        } catch (failure: WireDecodingException) {
            throw failure
        } catch (failure: Throwable) {
            throw WireDecodingException("Failed to parse client message", failure)
        }
    }

    override fun event(raw: String): Event = parseEvent(json.parseToJsonElement(raw))

    override fun filter(raw: String): Filter = parseFilter(json.parseToJsonElement(raw))

    private fun parseRelayEvent(array: JsonArray, raw: String): RelayMessage {
        if (array.size < 3) return RelayMessage.Unknown(raw, "EVENT frame expects 3 items")
        val subscriptionRaw = array[1].jsonPrimitive.content
        val subscriptionId = subscriptionRaw.toSubscriptionIdOrNull()
            ?: return RelayMessage.Unknown(raw, invalidSubscriptionReason(subscriptionRaw))
        val event = parseEvent(array[2])
        return RelayMessage.Event(subscriptionId, event)
    }

    private fun parseNotice(array: JsonArray, raw: String): RelayMessage {
        if (array.size < 2) return RelayMessage.Unknown(raw, "NOTICE frame expects 2 items")
        return RelayMessage.Notice(array[1].jsonPrimitive.content)
    }

    private fun parseEose(array: JsonArray, raw: String): RelayMessage {
        if (array.size < 2) return RelayMessage.Unknown(raw, "EOSE frame expects subscription id")
        val subscriptionRaw = array[1].jsonPrimitive.content
        val subscriptionId = subscriptionRaw.toSubscriptionIdOrNull()
            ?: return RelayMessage.Unknown(raw, invalidSubscriptionReason(subscriptionRaw))
        return RelayMessage.EndOfStoredEvents(subscriptionId)
    }

    private fun parseOk(array: JsonArray, raw: String): RelayMessage {
        if (array.size < 4) return RelayMessage.Unknown(raw, "OK frame expects 4 items")
        val eventId = array[1].jsonPrimitive.content
        val accepted = array[2].jsonPrimitive.booleanOrNull
            ?: return RelayMessage.Unknown(raw, "OK frame third element must be boolean")
        val message = array[3].jsonPrimitive.content
        val (code, normalized) = splitAckMessage(message)
        val result = PublishResult(eventId, accepted, normalized, code)
        return RelayMessage.Ok(result)
    }

    private fun parseClosed(array: JsonArray, raw: String): RelayMessage {
        if (array.size < 3) return RelayMessage.Unknown(raw, "CLOSED frame expects reason")
        val subscriptionRaw = array[1].jsonPrimitive.content
        val subscriptionId = subscriptionRaw.toSubscriptionIdOrNull()
            ?: return RelayMessage.Unknown(raw, invalidSubscriptionReason(subscriptionRaw))
        val rawReason = array[2].jsonPrimitive.content
        val (code, normalized) = splitAckMessage(rawReason)
        return RelayMessage.Closed(subscriptionId, normalized, code)
    }

    private fun parseAuth(array: JsonArray, raw: String): RelayMessage {
        if (array.size < 2) return RelayMessage.Unknown(raw, "AUTH frame expects challenge")
        return RelayMessage.AuthChallenge(array[1].jsonPrimitive.content)
    }

    private fun parseCount(array: JsonArray, raw: String): RelayMessage {
        if (array.size < 3) return RelayMessage.Unknown(raw, "COUNT frame expects result object")
        val subscriptionRaw = array[1].jsonPrimitive.content
        val subscriptionId = subscriptionRaw.toSubscriptionIdOrNull()
            ?: return RelayMessage.Unknown(raw, invalidSubscriptionReason(subscriptionRaw))
        val body = array[2] as? JsonObject
            ?: return RelayMessage.Unknown(raw, "COUNT body must be object")
        val count = body["count"]?.jsonPrimitive?.intOrNull
            ?: return RelayMessage.Unknown(raw, "COUNT body missing 'count'")
        return RelayMessage.Count(subscriptionId, count)
    }

    private fun eventToJson(event: Event): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(event.id))
        put("pubkey", JsonPrimitive(event.pubkey))
        put("created_at", JsonPrimitive(event.createdAt))
        put("kind", JsonPrimitive(event.kind))
        put("content", JsonPrimitive(event.content))
        put("sig", JsonPrimitive(event.sig))
        put("tags", buildJsonArray {
            event.tags.forEach { tag ->
                add(buildJsonArray {
                    tag.forEach { add(JsonPrimitive(it)) }
                })
            }
        })
    }

    private fun parseEvent(element: JsonElement): Event {
        val obj = element as? JsonObject
            ?: throw WireDecodingException("Event must be JSON object")
        val id = obj["id"]?.jsonPrimitive?.content
            ?: throw WireDecodingException("Event missing id")
        if (!id.isLowercaseHex(64)) {
            throw WireDecodingException("Event id must be 64 lowercase hex characters")
        }
        val pubkey = obj["pubkey"]?.jsonPrimitive?.content
            ?: throw WireDecodingException("Event missing pubkey")
        if (!pubkey.isLowercaseHex(64)) {
            throw WireDecodingException("Event pubkey must be 64 lowercase hex characters")
        }
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull
            ?: throw WireDecodingException("Event missing created_at")
        val kind = obj["kind"]?.jsonPrimitive?.intOrNull
            ?: throw WireDecodingException("Event missing kind")
        if (kind !in 0..Event.KIND_MAX) {
            throw WireDecodingException("Event kind must be between 0 and ${Event.KIND_MAX}")
        }
        val content = obj["content"]?.jsonPrimitive?.content ?: ""
        val sig = obj["sig"]?.jsonPrimitive?.content
            ?: throw WireDecodingException("Event missing sig")
        if (!sig.isLowercaseHex(128)) {
            throw WireDecodingException("Event signature must be 128 lowercase hex characters")
        }
        val tagsArray = obj["tags"] as? JsonArray ?: JsonArray(emptyList())
        val tags = tagsArray.map { tagElement ->
            val tagArray = tagElement as? JsonArray
                ?: throw WireDecodingException("Tag entry must be an array")
            if (tagArray.isEmpty()) {
                throw WireDecodingException("Tag entry must contain at least one string")
            }
            tagArray.map { it.jsonPrimitive.content }
        }
        return Event(id, pubkey, createdAt, kind, tags, content, sig)
    }

    private fun filterToJson(filter: Filter): JsonElement = buildJsonObject {
        filter.ids?.takeIf { it.isNotEmpty() }?.let { values ->
            put("ids", buildJsonArray {
                values.forEach { add(JsonPrimitive(it)) }
            })
        }
        filter.authors?.takeIf { it.isNotEmpty() }?.let { values ->
            put("authors", buildJsonArray {
                values.forEach { add(JsonPrimitive(it)) }
            })
        }
        filter.kinds?.takeIf { it.isNotEmpty() }?.let { numbers ->
            put("kinds", buildJsonArray {
                numbers.forEach { add(JsonPrimitive(it)) }
            })
        }
        filter.since?.let { put("since", JsonPrimitive(it)) }
        filter.until?.let { put("until", JsonPrimitive(it)) }
        filter.limit?.let { put("limit", JsonPrimitive(it)) }
        filter.tags.entries.sortedBy { it.key }.forEach { (key, values) ->
            put(key, buildJsonArray {
                values.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun parseFilter(element: JsonElement): Filter {
        val obj = element as? JsonObject ?: throw WireDecodingException("Filter must be a JSON object")
        val ids = obj["ids"]?.let { arrayAsStrings(it) }
        val authors = obj["authors"]?.let { arrayAsStrings(it) }
        val kinds = obj["kinds"]?.let { arrayAsInts(it) }
        val since = obj["since"]?.jsonPrimitive?.longOrNull
        val until = obj["until"]?.jsonPrimitive?.longOrNull
        val limit = obj["limit"]?.jsonPrimitive?.intOrNull
        val tagEntries = obj.entries
            .filter { (key, _) -> key.startsWith("#") }
            .associate { (key, value) -> key to arrayAsStrings(value).toSet() }
        return Filter(
            ids = ids?.toSet(),
            authors = authors?.toSet(),
            kinds = kinds?.toSet(),
            since = since,
            until = until,
            limit = limit,
            tags = tagEntries
        )
    }

    private fun arrayAsStrings(element: JsonElement): List<String> {
        val array = element as? JsonArray ?: throw WireDecodingException("Expected array of strings")
        return array.map { it.jsonPrimitive.content }
    }

    private fun arrayAsInts(element: JsonElement): List<Int> {
        val array = element as? JsonArray ?: throw WireDecodingException("Expected array of numbers")
        return array.map {
            it.jsonPrimitive.intOrNull ?: throw WireDecodingException("Expected integer value")
        }
    }

    private fun JsonArray.getOrThrow(index: Int): JsonElement = getOrNull(index)
        ?: throw WireDecodingException("Expected element at index $index")

    private fun String.toSubscriptionIdOrThrow(): SubscriptionId = try {
        SubscriptionId(this)
    } catch (failure: IllegalArgumentException) {
        throw WireDecodingException("Invalid subscription id: ${failure.message}", failure)
    }

    private fun String.toSubscriptionIdOrNull(): SubscriptionId? =
        runCatching { SubscriptionId(this) }.getOrNull()

    private fun invalidSubscriptionReason(raw: String): String =
        "invalid subscription id: '${raw.take(64)}'"

    private val JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else null

    /** Splits a relay acknowledgement message into machine-readable prefix and human-readable body. */
    private fun splitAckMessage(message: String): Pair<String?, String> {
        val delimiterIndex = message.indexOf(':')
        if (delimiterIndex <= 0) return null to message
        val prefix = message.substring(0, delimiterIndex).trim()
        val body = message.substring(delimiterIndex + 1).trimStart()
        return if (prefix in nostr.core.model.KNOWN_ACK_PREFIXES) prefix to body else null to message
    }
}
