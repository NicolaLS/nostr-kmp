package io.github.nicolals.nostr.core.primitives

import okio.ByteString
import okio.ByteString.Companion.decodeHex
import kotlin.jvm.JvmInline

/**
 * A 32-byte secp256k1 secret key (private key).
 *
 * This is the raw private key bytes used for signing events and deriving public keys.
 * Keep this value secure and never expose it.
 */
@JvmInline
value class SecretKey private constructor(val bytes: ByteString) {
    init {
        require(bytes.size == 32) { "SecretKey must be 32 bytes." }
    }

    /**
     * Returns the hex-encoded secret key.
     *
     * **Warning**: Be careful when using this method as it exposes the raw private key.
     */
    val hex: String get() = bytes.hex()

    companion object {
        fun fromHex(hex: String) = SecretKey(hex.decodeHex())

        fun fromBytes(bytes: ByteString) = SecretKey(bytes)
    }
}
