package io.github.nicolals.nostr.core.codec

interface JsonCodec {
    fun parse(json: String): JsonValue
    fun stringify(value: JsonValue): String
}
