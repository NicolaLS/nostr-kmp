@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreCrypto.CCOperation
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

internal actual fun aes256CbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray =
    aesCbc(operation = kCCEncrypt, key = key, iv = iv, input = plaintext)

internal actual fun aes256CbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
    aesCbc(operation = kCCDecrypt, key = key, iv = iv, input = ciphertext)

internal actual fun secureRandomBytes(length: Int): ByteArray {
    require(length >= 0) { "length must be non-negative" }
    val buffer = ByteArray(length)
    if (length == 0) return buffer
    buffer.usePinned {
        val status = SecRandomCopyBytes(kSecRandomDefault, length.toULong(), it.addressOf(0))
        require(status == errSecSuccess) { "SecRandomCopyBytes failed with status $status" }
    }
    return buffer
}

private fun aesCbc(operation: CCOperation, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray = memScoped {
    val output = ByteArray(input.size + kCCBlockSizeAES128.toInt())
    val outputLength = alloc<size_tVar>()
    val status = input.usePinned { inputPinned ->
        key.usePinned { keyPinned ->
            iv.usePinned { ivPinned ->
                output.usePinned { outputPinned ->
                    CCCrypt(
                        operation,
                        kCCAlgorithmAES,
                        kCCOptionPKCS7Padding,
                        keyPinned.addressOf(0),
                        key.size.toULong(),
                        ivPinned.addressOf(0),
                        inputPinned.addressOf(0),
                        input.size.toULong(),
                        outputPinned.addressOf(0),
                        output.size.toULong(),
                        outputLength.ptr
                    )
                }
            }
        }
    }
    require(status == kCCSuccess) { "CCCrypt failed with status $status" }
    output.copyOf(outputLength.value.toInt())
}
