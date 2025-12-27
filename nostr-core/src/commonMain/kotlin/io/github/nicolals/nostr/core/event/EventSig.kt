package io.github.nicolals.nostr.core.event

import okio.ByteString
import okio.ByteString.Companion.decodeHex
import kotlin.jvm.JvmInline

@JvmInline
value class EventSig(val bytes: ByteString) {
    init {
        require(bytes.size == 64) { "EventSig must be 64 bytes." }
    }

    val hex: String get() = bytes.hex()

    companion object Companion {
        fun fromHex(hex: String) = EventSig(hex.decodeHex())

        fun fromBytes(bytes: ByteString) = EventSig(bytes)
    }
}
