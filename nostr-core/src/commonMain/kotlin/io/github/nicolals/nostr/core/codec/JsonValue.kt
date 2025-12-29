package io.github.nicolals.nostr.core.codec

sealed interface JsonValue

data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue

data class JsonArray(val values: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

// Preserve the raw JSON number literal to avoid precision loss across platforms.
data class JsonNumber(val value: String) : JsonValue

data class JsonBoolean(val value: Boolean) : JsonValue

object JsonNull : JsonValue
