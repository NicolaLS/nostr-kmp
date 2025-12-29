package io.github.nicolals.nostr.core.codec

import io.github.nicolals.nostr.core.codec.wire.WireClientMessage
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.codec.wire.WireRelayMessage

interface NostrWireCodec {
    fun decodeEvent(value: JsonValue): WireEvent
    fun encodeEvent(event: WireEvent): JsonValue

    fun decodeFilter(value: JsonValue): WireFilter
    fun encodeFilter(filter: WireFilter): JsonValue

    fun decodeClientMessage(value: JsonValue): WireClientMessage
    fun encodeClientMessage(message: WireClientMessage): JsonValue

    fun decodeRelayMessage(value: JsonValue): WireRelayMessage
    fun encodeRelayMessage(message: WireRelayMessage): JsonValue
}

interface NostrJsonCodec : JsonCodec, NostrWireCodec {
    fun decodeEvent(json: String): WireEvent = decodeEvent(parse(json))
    fun encodeEventString(event: WireEvent): String = stringify(encodeEvent(event))

    fun decodeFilter(json: String): WireFilter = decodeFilter(parse(json))
    fun encodeFilterString(filter: WireFilter): String = stringify(encodeFilter(filter))

    fun decodeClientMessage(json: String): WireClientMessage = decodeClientMessage(parse(json))
    fun encodeClientMessageString(message: WireClientMessage): String =
        stringify(encodeClientMessage(message))

    fun decodeRelayMessage(json: String): WireRelayMessage = decodeRelayMessage(parse(json))
    fun encodeRelayMessageString(message: WireRelayMessage): String =
        stringify(encodeRelayMessage(message))
}
