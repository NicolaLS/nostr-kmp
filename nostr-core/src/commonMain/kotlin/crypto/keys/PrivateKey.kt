package nostr.core.crypto.keys

import kotlin.jvm.JvmInline
import nostr.core.crypto.HEX_CHARS_32
import nostr.core.utils.hexToByteArray
import nostr.core.utils.requireHex
import nostr.core.utils.toHexLower

private const val KEY_HEX_LENGTH = HEX_CHARS_32

/**
 * Immutable wrapper around a 32-byte secp256k1 private key expressed as lowercase hex.
 *
 * Instances can only be created through [fromHex] or [fromByteArray], which validate
 * length and character set. The `toString` implementation intentionally redacts the raw
 * value so logs do not leak secret material.
 */
@JvmInline
value class PrivateKey private constructor(val hex: String) {
    /** Returns the raw 32-byte secret key. */
    fun toByteArray(): ByteArray = hex.hexToByteArray()

    override fun toString(): String = "PrivateKey(****)"

    companion object {
        /** Validates and wraps a hex string (32 bytes / 64 chars) as a [PrivateKey]. */
        fun fromHex(hex: String): PrivateKey {
            val normalized = hex.lowercase()
            requireHex(normalized, KEY_HEX_LENGTH, "PrivateKey")
            return PrivateKey(normalized)
        }

        /** Creates a [PrivateKey] from raw bytes. The array must be exactly 32 bytes. */
        fun fromByteArray(bytes: ByteArray): PrivateKey {
            require(bytes.size == KEY_HEX_LENGTH / 2) { "PrivateKey must be 32 bytes." }
            return PrivateKey(bytes.toHexLower())
        }
    }
}
