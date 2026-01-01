package io.github.nicolals.nostr.core.codec

fun JsonCodec.parseOrNull(json: String): JsonValue? =
    runCatching { parse(json) }.getOrNull()

fun JsonCodec.parseObjectOrNull(json: String): JsonObject? =
    parseOrNull(json) as? JsonObject

fun JsonCodec.parseArrayOrNull(json: String): JsonArray? =
    parseOrNull(json) as? JsonArray

fun JsonValue?.nullIfJsonNull(): JsonValue? = when (this) {
    null, JsonNull -> null
    else -> this
}

fun JsonObject.valueOrNull(key: String): JsonValue? =
    entries[key].nullIfJsonNull()

fun JsonObject.stringValue(key: String): String? =
    (valueOrNull(key) as? JsonString)?.value

fun JsonObject.booleanValue(key: String): Boolean? =
    (valueOrNull(key) as? JsonBoolean)?.value

fun JsonObject.longValueOrNull(key: String): Long? = when (val value = valueOrNull(key)) {
    is JsonNumber -> value.value.toLongOrNull()
    is JsonString -> value.value.toLongOrNull()
    else -> null
}

fun JsonObject.intValueOrNull(key: String): Int? = when (val value = valueOrNull(key)) {
    is JsonNumber -> value.value.toIntOrNull()
    is JsonString -> value.value.toIntOrNull()
    else -> null
}

fun JsonObject.objectValue(key: String): JsonObject? =
    valueOrNull(key) as? JsonObject

fun JsonObject.arrayValue(key: String): JsonArray? =
    valueOrNull(key) as? JsonArray

fun JsonArray.stringValuesOrNull(): List<String>? {
    val values = mutableListOf<String>()
    for (value in this.values) {
        val str = (value as? JsonString)?.value ?: return null
        values += str
    }
    return values
}

fun jsonObjectOf(vararg entries: Pair<String, JsonValue?>): JsonObject {
    val map = linkedMapOf<String, JsonValue>()
    for ((key, value) in entries) {
        if (value != null) {
            map[key] = value
        }
    }
    return JsonObject(map)
}
