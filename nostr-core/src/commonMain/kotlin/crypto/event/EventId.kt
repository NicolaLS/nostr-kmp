package nostr.core.crypto.event

import nostr.core.crypto.HEX_CHARS_32
import nostr.core.utils.hexToByteArray
import nostr.core.utils.requireHex
import nostr.core.utils.toHexLower

private const val KEY_HEX_LENGTH = HEX_CHARS_32

/**
 * Strongly typed wrapper around the 32-byte SHA-256 hash of a canonical Nostr event.
 * Used anywhere IDs are passed around to avoid accidental string formatting mistakes.
 */
@JvmInline
value class EventId private constructor(val hex: String) {
    /** Returns the raw 32-byte hash. */
    fun toByteArray(): ByteArray = hex.hexToByteArray()

    override fun toString(): String = hex

    companion object {
        /** Validates a hexadecimal representation and wraps it as an [EventId]. */
        fun fromHex(hex: String): EventId {
            val normalized = hex.lowercase()
            requireHex(normalized, KEY_HEX_LENGTH, "EventId")
            return EventId(normalized)
        }

        /** Creates an [EventId] from raw bytes. Requires exactly 32 bytes. */
        fun fromByteArray(bytes: ByteArray): EventId {
            require(bytes.size == KEY_HEX_LENGTH / 2) { "EventId must be 32 bytes." }
            return EventId(bytes.toHexLower())
        }
    }
}
