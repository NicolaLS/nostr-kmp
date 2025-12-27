package io.github.nicolals.nostr.core.types

import kotlin.jvm.JvmInline

@JvmInline
value class UnixTimeSeconds(val value: Long) {
    companion object {
        fun now(clock: () -> Long = { currentTimeMillis() }): UnixTimeSeconds =
            UnixTimeSeconds(clock() / 1000)
    }
}

// TODO: expect/actual clock for KMP. Maybe add a new package with interface so it can be reused not only here
private fun currentTimeMillis(): Long = TODO("Not implemented yet")
