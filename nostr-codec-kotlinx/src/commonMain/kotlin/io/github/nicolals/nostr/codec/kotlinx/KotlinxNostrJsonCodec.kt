package io.github.nicolals.nostr.codec.kotlinx

import io.github.nicolals.nostr.core.codec.*
import io.github.nicolals.nostr.core.codec.wire.*
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.JsonArray as KxJsonArray
import kotlinx.serialization.json.JsonElement as KxJsonElement
import kotlinx.serialization.json.JsonNull as KxJsonNull
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.JsonPrimitive as KxJsonPrimitive

class KotlinxNostrJsonCodec : NostrJsonCodec {
    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(json: String): JsonValue = jsonElementToValue(this.json.parseToJsonElement(json))

    override fun stringify(value: JsonValue): String =
        json.encodeToString(jsonValueToElement(value))

    override fun decodeEvent(value: JsonValue): WireEvent {
        val obj = value.requireObject("event")
        return WireEvent(
            id = obj.requireString("id"),
            pubkey = obj.requireString("pubkey"),
            createdAt = obj.requireLong("created_at"),
            kind = obj.requireInt("kind"),
            tags = obj.requireArray("tags").values.mapIndexed { index, tag ->
                tag.requireStringList("tags[$index]")
            },
            content = obj.requireString("content"),
            sig = obj.requireString("sig"),
        )
    }

    override fun encodeEvent(event: WireEvent): JsonValue = JsonObject(
        linkedMapOf(
            "id" to JsonString(event.id),
            "pubkey" to JsonString(event.pubkey),
            "created_at" to JsonNumber(event.createdAt.toString()),
            "kind" to JsonNumber(event.kind.toString()),
            "tags" to JsonArray(event.tags.map { tag -> JsonArray(tag.map { JsonString(it) }) }),
            "content" to JsonString(event.content),
            "sig" to JsonString(event.sig),
        ),
    )

    override fun decodeFilter(value: JsonValue): WireFilter {
        val obj = value.requireObject("filter")
        val ids = obj.valueOrNull("ids")?.requireStringList("ids")
        val authors = obj.valueOrNull("authors")?.requireStringList("authors")
        val kinds = obj.valueOrNull("kinds")?.requireLongList("kinds")
        val since = obj.valueOrNull("since")?.requireLong("since")
        val until = obj.valueOrNull("until")?.requireLong("until")
        val limit = obj.valueOrNull("limit")?.requireInt("limit")
        val tagFilters = obj.entries
            .filterKeys { key -> key.startsWith("#") && key.length > 1 }
            .mapNotNull { (key, tagValue) ->
                val normalizedKey = normalizeTagFilterKey(key)
                tagValue.nullIfJsonNull()
                    ?.let { normalizedKey to it.requireStringList("tag filter #$normalizedKey") }
            }
            .toMap()
            .ifEmpty { null }

        return WireFilter(
            ids = ids,
            authors = authors,
            kinds = kinds,
            tagFilters = tagFilters,
            since = since,
            until = until,
            limit = limit,
        )
    }

    override fun encodeFilter(filter: WireFilter): JsonValue {
        val entries = linkedMapOf<String, JsonValue>()
        filter.ids?.let { values -> entries["ids"] = JsonArray(values.map { JsonString(it) }) }
        filter.authors?.let { values -> entries["authors"] = JsonArray(values.map { JsonString(it) }) }
        filter.kinds?.let { values -> entries["kinds"] = JsonArray(values.map { JsonNumber(it.toString()) }) }
        filter.tagFilters
            ?.entries
            ?.sortedBy { it.key }
            ?.forEach { (key, values) ->
                val normalizedKey = normalizeTagFilterKey(key)
                entries["#$normalizedKey"] = JsonArray(values.map { JsonString(it) })
            }
        filter.since?.let { value -> entries["since"] = JsonNumber(value.toString()) }
        filter.until?.let { value -> entries["until"] = JsonNumber(value.toString()) }
        filter.limit?.let { value -> entries["limit"] = JsonNumber(value.toString()) }
        return JsonObject(entries)
    }

    override fun decodeClientMessage(value: JsonValue): RawClientMessage {
        val array = value.requireArray("client message")
        return RawClientMessage(array)
    }

    override fun decodeRelayMessage(value: JsonValue): RawRelayMessage {
        val array = value.requireArray("relay message")
        return RawRelayMessage(array)
    }
}

private fun jsonElementToValue(element: KxJsonElement): JsonValue = when (element) {
    is KxJsonObject -> JsonObject(element.mapValues { (_, value) -> jsonElementToValue(value) })
    is KxJsonArray -> JsonArray(element.map(::jsonElementToValue))
    is KxJsonNull -> JsonNull
    is KxJsonPrimitive -> when {
        element.isString -> JsonString(element.content)
        element.content.toBooleanStrictOrNull() != null -> JsonBoolean(element.content.toBoolean())
        else -> JsonNumber(element.content)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun jsonValueToElement(value: JsonValue): KxJsonElement = when (value) {
    is JsonObject -> KxJsonObject(value.entries.mapValues { (_, entryValue) -> jsonValueToElement(entryValue) })
    is JsonArray -> KxJsonArray(value.values.map(::jsonValueToElement))
    is JsonString -> KxJsonPrimitive(value.value)
    is JsonNumber -> JsonUnquotedLiteral(requireValidJsonNumberLiteral(value.value))
    is JsonBoolean -> KxJsonPrimitive(value.value)
    JsonNull -> KxJsonNull
}

private fun JsonValue.requireObject(label: String): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("Expected object for $label")

private fun JsonValue.requireArray(label: String): JsonArray =
    this as? JsonArray ?: throw IllegalArgumentException("Expected array for $label")

private fun JsonValue.requireString(label: String): String =
    (this as? JsonString)?.value ?: throw IllegalArgumentException("Expected string for $label")

private fun JsonValue.requireBoolean(label: String): Boolean =
    (this as? JsonBoolean)?.value ?: throw IllegalArgumentException("Expected boolean for $label")

private fun JsonValue.requireNumber(label: String): String =
    (this as? JsonNumber)?.value ?: throw IllegalArgumentException("Expected number for $label")

private fun JsonValue.requireLong(label: String): Long = requireNumber(label).toLongStrict(label)

private fun JsonValue.requireInt(label: String): Int = requireNumber(label).toIntStrict(label)

private fun JsonValue.requireStringList(label: String): List<String> {
    val array = requireArray(label)
    return array.values.mapIndexed { index, value -> value.requireString("$label[$index]") }
}

private fun JsonValue.requireLongList(label: String): List<Long> {
    val array = requireArray(label)
    return array.values.mapIndexed { index, value -> value.requireLong("$label[$index]") }
}

private fun JsonValue?.nullIfJsonNull(): JsonValue? = when (this) {
    null, JsonNull -> null
    else -> this
}

private fun normalizeTagFilterKey(rawKey: String): String {
    val normalized = rawKey.trimStart('#')
    if (normalized.isEmpty()) {
        throw IllegalArgumentException("Expected tag filter key to include a name but was '$rawKey'")
    }
    return normalized
}

private val jsonNumberRegex = Regex("^(-)?(0|[1-9]\\d*)(?:\\.(\\d+))?(?:[eE]([+-]?\\d+))?$")

private fun requireValidJsonNumberLiteral(raw: String): String {
    if (!jsonNumberRegex.matches(raw)) {
        throw IllegalArgumentException("Invalid JSON number literal: $raw")
    }
    return raw
}

private fun String.toLongStrict(label: String): Long = parseJsonNumberToLong(this, label)

private fun String.toIntStrict(label: String): Int {
    val value = parseJsonNumberToLong(this, label)
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw IllegalArgumentException("Expected int for $label")
    }
    return value.toInt()
}

private fun parseJsonNumberToLong(raw: String, label: String): Long {
    val match = jsonNumberRegex.matchEntire(raw) ?: throw IllegalArgumentException("Expected number for $label")
    val negative = match.groupValues[1] == "-"
    val intPart = match.groupValues[2]
    val fracPart = match.groupValues[3]
    val expPart = match.groupValues[4]

    var digits = intPart + fracPart
    if (digits.all { it == '0' }) {
        return 0L
    }

    val scale = fracPart.length
    val exponent = expPart.toIntOrNull() ?: 0
    val shift = exponent - scale
    if (shift < 0) {
        val trim = -shift
        if (digits.length <= trim) {
            throw IllegalArgumentException("Expected integer for $label")
        }
        val fractionalDigits = digits.takeLast(trim)
        if (fractionalDigits.any { it != '0' }) {
            throw IllegalArgumentException("Expected integer for $label")
        }
        digits = digits.dropLast(trim)
    } else if (shift > 0) {
        digits += "0".repeat(shift)
    }

    val unsigned = digits.toLongOrNull() ?: throw IllegalArgumentException("Expected integer for $label")
    return if (negative) -unsigned else unsigned
}

private fun JsonObject.requireValue(key: String): JsonValue =
    entries[key] ?: throw IllegalArgumentException("Missing value for '$key'")

private fun JsonObject.valueOrNull(key: String): JsonValue? = entries[key].nullIfJsonNull()

private fun JsonObject.requireString(key: String): String = requireValue(key).requireString("field '$key'")

private fun JsonObject.requireArray(key: String): JsonArray = requireValue(key).requireArray("field '$key'")

private fun JsonObject.requireLong(key: String): Long = requireValue(key).requireLong("field '$key'")

private fun JsonObject.requireInt(key: String): Int = requireValue(key).requireInt("field '$key'")
