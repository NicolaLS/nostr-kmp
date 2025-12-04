package nostr.runtime.coroutines

import java.net.NetworkInterface

/**
 * Android implementation checks if any non-loopback network interface is up.
 *
 * This doesn't require Context and works reliably for detecting when
 * the device is completely offline (airplane mode, no connectivity).
 */
internal actual fun isNetworkAvailable(): Boolean {
    return try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()?.any { iface ->
            iface.isUp && !iface.isLoopback && iface.inetAddresses.hasMoreElements()
        } ?: false
    } catch (_: Exception) {
        // If we can't determine, assume network is available
        true
    }
}
