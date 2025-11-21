package nostr.core.crypto

import java.security.MessageDigest

private val sha256ThreadLocal: ThreadLocal<MessageDigest> =
    ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

actual fun sha256(input: ByteArray): ByteArray {
    val digest = sha256ThreadLocal.get()
    digest.reset()
    return digest.digest(input)
}
