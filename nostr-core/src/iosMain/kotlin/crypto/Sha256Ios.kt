package nostr.core.crypto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import nostr.core.crypto.interop.nostr_sha256_bridge
import kotlin.experimental.ExperimentalNativeApi

private const val SHA256_DIGEST_LENGTH = 32

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual fun sha256(input: ByteArray): ByteArray {
    val output = ByteArray(SHA256_DIGEST_LENGTH)
    input.usePinned { pinnedInput ->
        output.usePinned { pinnedOutput ->
            nostr_sha256_bridge(
                pinnedInput.addressOf(0).reinterpret<UByteVar>(),
                input.size.convert(),
                pinnedOutput.addressOf(0).reinterpret<UByteVar>()
            )
        }
    }
    return output
}
