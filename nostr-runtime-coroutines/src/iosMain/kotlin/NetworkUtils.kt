package nostr.runtime.coroutines

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable

/**
 * iOS implementation uses SCNetworkReachability to check connectivity.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun isNetworkAvailable(): Boolean {
    return memScoped {
        val reachability = SCNetworkReachabilityCreateWithName(null, "apple.com")
            ?: return@memScoped true // If we can't create reachability, assume available

        val flags = alloc<platform.SystemConfiguration.SCNetworkReachabilityFlagsVar>()
        val gotFlags = SCNetworkReachabilityGetFlags(reachability, flags.ptr)

        if (!gotFlags) {
            return@memScoped true // If we can't get flags, assume available
        }

        val isReachable = (flags.value and kSCNetworkReachabilityFlagsReachable) != 0u
        isReachable
    }
}
