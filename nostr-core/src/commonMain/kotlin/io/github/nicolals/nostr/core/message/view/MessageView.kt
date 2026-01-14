package io.github.nicolals.nostr.core.message.view

import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import kotlin.jvm.JvmInline

/**
 * Base interface for all typed client message views.
 *
 * Similar to [io.github.nicolals.nostr.core.event.view.EventView] for events,
 * this interface allows NIP modules to provide typed interpretations of
 * raw protocol messages.
 *
 * Implementations should be cheap to construct. Expensive operations
 * (like decryption or complex parsing) should use lazy properties.
 */
interface ClientMessageView {
    /** The underlying raw message */
    val raw: RawClientMessage

    /** Convenience accessor for the message token */
    val token: String get() = raw.token
}

/**
 * Base interface for all typed relay message views.
 *
 * Similar to [io.github.nicolals.nostr.core.event.view.EventView] for events,
 * this interface allows NIP modules to provide typed interpretations of
 * raw protocol messages.
 *
 * Implementations should be cheap to construct. Expensive operations
 * (like decryption or complex parsing) should use lazy properties.
 */
interface RelayMessageView {
    /** The underlying raw message */
    val raw: RawRelayMessage

    /** Convenience accessor for the message token */
    val token: String get() = raw.token
}

/**
 * Fallback view for client messages that could not be upgraded to a typed view.
 *
 * Similar to [io.github.nicolals.nostr.core.event.view.GenericEvent], this
 * allows handling of unrecognized messages without failing.
 *
 * This view is returned in two cases:
 * 1. **Unrecognized token**: No upgrader is registered for the message's token
 *    (e.g., a NIP extension message when only NIP-01 is installed)
 * 2. **Malformed payload**: An upgrader recognized the token but the payload
 *    structure was invalid (e.g., an EVENT message missing required fields)
 *
 * To distinguish between these cases, inspect [token] (via [raw]):
 * - If [token] matches a known message type (e.g., "EVENT", "REQ"), the message
 *   was likely malformed
 * - If [token] is unrecognized, the message is from an unsupported protocol extension
 */
@JvmInline
value class UnknownClientMessage(override val raw: RawClientMessage) : ClientMessageView

/**
 * Fallback view for relay messages that could not be upgraded to a typed view.
 *
 * Similar to [io.github.nicolals.nostr.core.event.view.GenericEvent], this
 * allows handling of unrecognized messages without failing.
 *
 * This view is returned in two cases:
 * 1. **Unrecognized token**: No upgrader is registered for the message's token
 *    (e.g., a NIP-42 AUTH message when only NIP-01 is installed)
 * 2. **Malformed payload**: An upgrader recognized the token but the payload
 *    structure was invalid (e.g., an EVENT message missing the event object)
 *
 * To distinguish between these cases, inspect [token] (via [raw]):
 * - If [token] matches a known message type (e.g., "EVENT", "OK"), the message
 *   was likely malformed
 * - If [token] is unrecognized, the message is from an unsupported protocol extension
 */
@JvmInline
value class UnknownRelayMessage(override val raw: RawRelayMessage) : RelayMessageView
