package nostr.core.time

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual object SystemClock : Clock {
    actual override fun nowSeconds(): Long = NSDate().timeIntervalSince1970.toLong()
}
