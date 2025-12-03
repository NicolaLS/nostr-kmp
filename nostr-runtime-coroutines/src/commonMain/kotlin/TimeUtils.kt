package nostr.runtime.coroutines

/**
 * Returns the current time in milliseconds since epoch.
 * Platform-specific implementation.
 */
internal expect fun currentTimeMillis(): Long
