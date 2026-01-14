package io.github.nicolals.nostr.core.codec

import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.message.view.RelayMessageView

/**
 * Codec for Nostr wire format.
 *
 * Handles encoding/decoding of events, filters, and raw protocol messages.
 * Protocol message interpretation is delegated to NIP module upgraders.
 */
interface NostrWireCodec {
    /** Decode an event from JSON */
    fun decodeEvent(value: JsonValue): WireEvent

    /** Encode an event to JSON */
    fun encodeEvent(event: WireEvent): JsonValue

    /** Decode a filter from JSON */
    fun decodeFilter(value: JsonValue): WireFilter

    /** Encode a filter to JSON */
    fun encodeFilter(filter: WireFilter): JsonValue

    /**
     * Decode a client message from JSON to its raw form.
     * Use [io.github.nicolals.nostr.core.message.upgrade.MessageUpgradeRegistry] to
     * convert to typed views.
     */
    fun decodeClientMessage(value: JsonValue): RawClientMessage

    /**
     * Encode a client message view to JSON.
     * Default implementation returns the raw array from the view.
     */
    fun encodeClientMessage(message: ClientMessageView): JsonValue = message.raw.array

    /**
     * Decode a relay message from JSON to its raw form.
     * Use [io.github.nicolals.nostr.core.message.upgrade.MessageUpgradeRegistry] to
     * convert to typed views.
     */
    fun decodeRelayMessage(value: JsonValue): RawRelayMessage

    /**
     * Encode a relay message view to JSON.
     * Default implementation returns the raw array from the view.
     */
    fun encodeRelayMessage(message: RelayMessageView): JsonValue = message.raw.array
}

/**
 * Combined JSON and Nostr wire codec.
 */
interface NostrJsonCodec : JsonCodec, NostrWireCodec {
    fun decodeEvent(json: String): WireEvent = decodeEvent(parse(json))
    fun encodeEventString(event: WireEvent): String = stringify(encodeEvent(event))

    fun decodeFilter(json: String): WireFilter = decodeFilter(parse(json))
    fun encodeFilterString(filter: WireFilter): String = stringify(encodeFilter(filter))

    fun decodeClientMessage(json: String): RawClientMessage = decodeClientMessage(parse(json))
    fun encodeClientMessageString(message: ClientMessageView): String =
        stringify(encodeClientMessage(message))

    fun decodeRelayMessage(json: String): RawRelayMessage = decodeRelayMessage(parse(json))
    fun encodeRelayMessageString(message: RelayMessageView): String =
        stringify(encodeRelayMessage(message))
}
