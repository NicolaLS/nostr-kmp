package nostr.core.utils

/**
 * Hex-encodes this [ByteArray] into a **lowercase** ASCII string.
 *
 * Fast, allocation-lean (one [CharArray] + the resulting [String]),
 * and fully multiplatform (JVM/Android/JS/Native).
 */
fun ByteArray.toHexLower(): String {
    if (isEmpty()) return ""
    val out = CharArray(size * 2)
    var i = 0
    for (b in this) {
        val v = b.toInt() and 0xFF
        out[i++] = HEX_LOWER[v ushr 4]
        out[i++] = HEX_LOWER[v and 0x0F]
    }
    return out.concatToString()
}

/** Returns true when every character is a valid hex digit (case-insensitive). */
fun String.isHex(expectedLength: Int? = null): Boolean {
    if (expectedLength != null && length != expectedLength) return false
    for (ch in this) {
        val lower = ch.lowercaseChar()
        if (lower !in HEX_LOWER_SET) return false
    }
    return true
}

/** Returns true when every character is a lowercase hex digit. */
fun String.isLowercaseHex(expectedLength: Int? = null): Boolean {
    if (expectedLength != null && length != expectedLength) return false
    for (ch in this) {
        if (ch !in HEX_LOWER_SET) return false
    }
    return true
}

/** Decodes the string into a byte array. The string must contain an even number of characters. */
fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    val lower = lowercase()
    val bytes = ByteArray(length / 2)
    var i = 0
    while (i < bytes.size) {
        val hi = HEX_LOWER.indexOf(lower[i * 2])
        val lo = HEX_LOWER.indexOf(lower[i * 2 + 1])
        if (hi == -1 || lo == -1) {
            throw IllegalArgumentException("Invalid hex character in '$this'")
        }
        bytes[i] = ((hi shl 4) or lo).toByte()
        i++
    }
    return bytes
}

private val HEX_LOWER = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
)

private val HEX_LOWER_SET = HEX_LOWER.toSet()


/** Validates that [hex] is lowercase hexadecimal with the expected [length]. */
fun requireHex(hex: String, length: Int, label: String) {
    require(hex.length == length) { "$label hex must be $length characters." }
    for (ch in hex) {
        require(ch in '0'..'9' || ch in 'a'..'f') { "$label hex contains invalid character '$ch'." }
    }
}
