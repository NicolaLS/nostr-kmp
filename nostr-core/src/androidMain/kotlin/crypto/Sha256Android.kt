package nostr.core.crypto

import java.security.MessageDigest

actual fun sha256(input: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input)
}
