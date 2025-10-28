package nostr.core.time

actual object SystemClock : Clock {
    actual override fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
