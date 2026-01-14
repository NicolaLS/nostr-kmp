package io.github.nicolals.nostr.core.message.raw

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.JsonValue
import kotlin.jvm.JvmInline

/**
 * Raw client message - the JSON array as received/sent over the wire.
 *
 * This is a thin wrapper providing token extraction. The actual interpretation
 * of the message is done by [io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader]
 * implementations registered by NIP modules.
 */
@JvmInline
value class RawClientMessage(val array: JsonArray) {
    /**
     * The message type token (e.g., "EVENT", "REQ", "AUTH"), or null if invalid.
     */
    val tokenOrNull: String?
        get() = (array.values.firstOrNull() as? JsonString)?.value

    /**
     * The message type token (e.g., "EVENT", "REQ", "AUTH").
     * @throws IllegalArgumentException if the array is empty or first element is not a string
     */
    val token: String
        get() = tokenOrNull
            ?: throw IllegalArgumentException("Invalid client message: expected string token at index 0")

    /**
     * Number of payload elements (everything after the token).
     * Zero-allocation access.
     */
    val payloadSize: Int
        get() = (array.values.size - 1).coerceAtLeast(0)

    /**
     * Get a payload element by index (0-based, relative to payload start).
     * Zero-allocation access.
     * @throws IndexOutOfBoundsException if index is out of range
     */
    fun payloadAt(index: Int): JsonValue = array.values[index + 1]

    /**
     * Get a payload element by index, or null if out of bounds.
     * Zero-allocation access.
     */
    fun payloadAtOrNull(index: Int): JsonValue? = array.values.getOrNull(index + 1)
}

/**
 * Raw relay message - the JSON array as received from the relay.
 *
 * This is a thin wrapper providing token extraction. The actual interpretation
 * of the message is done by [io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader]
 * implementations registered by NIP modules.
 */
@JvmInline
value class RawRelayMessage(val array: JsonArray) {
    /**
     * The message type token (e.g., "EVENT", "OK", "AUTH"), or null if invalid.
     */
    val tokenOrNull: String?
        get() = (array.values.firstOrNull() as? JsonString)?.value

    /**
     * The message type token (e.g., "EVENT", "OK", "AUTH").
     * @throws IllegalArgumentException if the array is empty or first element is not a string
     */
    val token: String
        get() = tokenOrNull
            ?: throw IllegalArgumentException("Invalid relay message: expected string token at index 0")

    /**
     * Number of payload elements (everything after the token).
     * Zero-allocation access.
     */
    val payloadSize: Int
        get() = (array.values.size - 1).coerceAtLeast(0)

    /**
     * Get a payload element by index (0-based, relative to payload start).
     * Zero-allocation access.
     * @throws IndexOutOfBoundsException if index is out of range
     */
    fun payloadAt(index: Int): JsonValue = array.values[index + 1]

    /**
     * Get a payload element by index, or null if out of bounds.
     * Zero-allocation access.
     */
    fun payloadAtOrNull(index: Int): JsonValue? = array.values.getOrNull(index + 1)
}
