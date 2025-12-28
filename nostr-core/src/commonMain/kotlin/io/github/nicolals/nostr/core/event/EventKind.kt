package io.github.nicolals.nostr.core.event

import kotlin.jvm.JvmInline

@JvmInline
value class EventKind(val value: Int) {
    val isRegular get() = value == 1 || value == 2 || value in 4..<45 || value in 1_000..<10_000
    val isEphemeral get() = value in 20_000..<30_000
    val isReplaceable get() = value == 0 || value == 3 || value in 10_000..<20_000
    val isAddressable get() = value in 30_000..<40_000

    init {
        // integer between 0 and 65535
        require(value in 0..65535)
    }
}