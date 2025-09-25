package nostr.core.time

actual object SystemClock : Clock {
    override fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}

