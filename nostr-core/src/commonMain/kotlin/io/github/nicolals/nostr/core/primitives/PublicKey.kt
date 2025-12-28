package io.github.nicolals.nostr.core.primitives

import okio.ByteString
import okio.ByteString.Companion.decodeHex
import kotlin.jvm.JvmInline

@JvmInline
value class PublicKey private constructor(val bytes: ByteString) {
    init {
        require(bytes.size == 32) { "PublicKey must be 32 bytes." }
    }

    val hex: String get() = bytes.hex()

    companion object {
        fun fromHex(hex: String) = PublicKey(hex.decodeHex())

        fun fromBytes(bytes: ByteString) = PublicKey(bytes)
    }
}