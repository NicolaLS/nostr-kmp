package nostr.core.relay

/** Raised when an established relay connection becomes idle beyond the configured threshold. */
class IdleTimeoutException(
    val relayUrl: String,
    val idleMillis: Long
) : Exception("Idle timeout after ${idleMillis}ms for $relayUrl")
