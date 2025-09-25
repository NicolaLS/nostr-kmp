package nostr.core.time

internal object JvmSystemClock : Clock {
    override fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}

actual object SystemClock : Clock by JvmSystemClock

