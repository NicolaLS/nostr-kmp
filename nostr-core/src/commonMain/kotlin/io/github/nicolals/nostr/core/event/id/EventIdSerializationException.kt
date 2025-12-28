package io.github.nicolals.nostr.core.event.id

/**
 * Thrown when NIP-01 JSON serialization encounters ambiguous input that could
 * lead to inconsistent ID hashes across clients.
 */
class EventIdSerializationException(message: String) : IllegalArgumentException(message) {
    companion object Companion {
        fun invalidControlChar(context: String, ch: Char): EventIdSerializationException {
            val hex = ch.code.toString(16).uppercase().padStart(4, '0')
            val message = "NIP-01 serialization encountered control character U+$hex in $context. " +
                    "NIP-01 does not define how to escape this, which can lead to invalid JSON and " +
                    "inconsistent ID hashes across clients. To avoid a false sense of security, this " +
                    "library refuses to compute or verify IDs/signatures for such events. For new " +
                    "events, avoid control characters."
            return EventIdSerializationException(message)
        }
    }
}
