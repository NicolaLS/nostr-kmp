package nostr.runtime.coroutines

import java.net.NetworkInterface

/**
 * JVM implementation checks if any non-loopback network interface is up.
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
