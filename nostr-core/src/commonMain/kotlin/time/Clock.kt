package nostr.core.time

/** Minimal clock abstraction returning epoch seconds. */
interface Clock {
    fun nowSeconds(): Long
}
