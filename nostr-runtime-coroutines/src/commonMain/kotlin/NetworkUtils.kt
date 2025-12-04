package nostr.runtime.coroutines

/**
 * Checks if the device currently has network connectivity.
 *
 * This is a best-effort check - it may return true even if the network
 * is actually unreachable (e.g., connected to WiFi but no internet).
 * However, it should return false when the device is clearly offline
 * (e.g., airplane mode, WiFi disabled with no cellular).
 *
 * Platform-specific implementation.
 */
internal expect fun isNetworkAvailable(): Boolean
