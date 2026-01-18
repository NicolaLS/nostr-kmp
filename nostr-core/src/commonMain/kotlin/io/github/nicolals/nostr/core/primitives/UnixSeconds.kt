package io.github.nicolals.nostr.core.primitives

import kotlin.jvm.JvmInline

@JvmInline
value class UnixTimeSeconds(val value: Long) {
    companion object {
        /**
         * Returns the current Unix timestamp in seconds.
         *
         * @param clock Optional clock function returning milliseconds since epoch.
         *              Defaults to the system clock. Pass a custom function for testing.
         */
        fun now(clock: () -> Long = ::currentTimeMillis): UnixTimeSeconds =
            UnixTimeSeconds(clock() / 1000)
    }
}

/**
 * Returns the current time in milliseconds since the Unix epoch.
 * Platform-specific implementation.
 */
internal expect fun currentTimeMillis(): Long
