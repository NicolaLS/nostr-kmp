package nostr.core.crypto.event

import kotlin.jvm.JvmInline
import nostr.core.crypto.HEX_CHARS_64
import nostr.core.utils.hexToByteArray
import nostr.core.utils.requireHex
import nostr.core.utils.toHexLower

/**
 * Schnorr signature encoded as lowercase hex. The value is treated as opaque—validation
 * only checks length and character set. Signature interpretation is delegated to
 * verifiers supplied by `nostr-crypto` or another backend.
 */
@JvmInline
value class Signature private constructor(val hex: String) {
    /** Returns the raw 64-byte Schnorr signature. */
    fun toByteArray(): ByteArray = hex.hexToByteArray()

    override fun toString(): String = hex

    companion object {
        private const val SIGNATURE_HEX_LENGTH = HEX_CHARS_64

        /** Validates a hex string and wraps it as a [Signature]. */
        fun fromHex(hex: String): Signature {
            val normalized = hex.lowercase()
            requireHex(normalized, SIGNATURE_HEX_LENGTH, "Signature")
            return Signature(normalized)
        }

        /** Creates a [Signature] from raw bytes. Requires exactly 64 bytes. */
        fun fromByteArray(bytes: ByteArray): Signature {
            require(bytes.size == SIGNATURE_HEX_LENGTH / 2) { "Signature must be 64 bytes." }
            return Signature(bytes.toHexLower())
        }
    }
}
