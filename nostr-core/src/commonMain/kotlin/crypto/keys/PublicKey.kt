package nostr.core.crypto.keys

import nostr.core.crypto.HEX_CHARS_32
import nostr.core.utils.hexToByteArray
import nostr.core.utils.requireHex
import nostr.core.utils.toHexLower

private const val KEY_HEX_LENGTH = HEX_CHARS_32

/**
 * X-only secp256k1 public key represented as lowercase hex. The value corresponds to
 * the 32-byte x coordinate defined by NIP-01. Construction routines guarantee the
 * string length and character set are valid.
 */
@JvmInline
value class PublicKey private constructor(val hex: String) {
    /** Returns the raw 32-byte x-only public key. */
    fun toByteArray(): ByteArray = hex.hexToByteArray()

    override fun toString(): String = hex

    companion object {
        /** Validates a hex string and wraps it as a [PublicKey]. */
        fun fromHex(hex: String): PublicKey {
            val normalized = hex.lowercase()
            requireHex(normalized, KEY_HEX_LENGTH, "PublicKey")
            return PublicKey(normalized)
        }

        /** Creates a [PublicKey] from raw bytes. Expects exactly 32 bytes. */
        fun fromByteArray(bytes: ByteArray): PublicKey {
            require(bytes.size == KEY_HEX_LENGTH / 2) { "PublicKey must be 32 bytes." }
            return PublicKey(bytes.toHexLower())
        }
    }
}
