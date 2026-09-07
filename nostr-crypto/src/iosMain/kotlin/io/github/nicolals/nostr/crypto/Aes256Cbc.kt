package io.github.nicolals.nostr.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCKeySizeAES256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import kotlin.text.toInt

@OptIn(ExperimentalForeignApi::class)
internal actual object Aes256Cbc {
    private const val BLOCK_SIZE = 16
    private const val KEY_SIZE = 32

    actual fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        return crypt(kCCEncrypt, key, iv, plaintext)
    }

    actual fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        require(ciphertext.size % BLOCK_SIZE == 0) { "ciphertext length must be multiple of 16" }
        // CommonCrypto's padding removal accepts malformed suffixes. Keep the raw final block
        // and validate PKCS7 ourselves so every platform enforces the same decryption contract.
        val padded = crypt(kCCDecrypt, key, iv, ciphertext)
        require(padded.size == ciphertext.size) { "invalid decrypted length" }
        val paddingSize = padded.last().toInt() and 0xff
        var invalid = ((paddingSize - 1) shr 31) or ((BLOCK_SIZE - paddingSize) shr 31)
        for (offset in 1..BLOCK_SIZE) {
            val paddingMask = (offset - paddingSize - 1) shr 31
            val value = padded[padded.size - offset].toInt() and 0xff
            invalid = invalid or ((value xor paddingSize) and paddingMask)
        }
        require(invalid == 0) { "invalid PKCS7 padding" }
        return padded.copyOf(padded.size - paddingSize)
    }

    private fun crypt(
        operation: UInt,
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "key must be 32 bytes" }
        require(iv.size == BLOCK_SIZE) { "iv must be 16 bytes" }
        val output = ByteArray(input.size + BLOCK_SIZE)
        var outputSize = 0
        memScoped {
            key.usePinned { keyPinned ->
                iv.usePinned { ivPinned ->
                    input.usePinned { inputPinned ->
                        output.usePinned { outputPinned ->
                            val outLength = alloc<ULongVar>()
                            val status = CCCrypt(
                                op = operation.convert(),
                                alg = kCCAlgorithmAES128.convert(),
                                options = if (operation == kCCEncrypt) kCCOptionPKCS7Padding.convert() else 0u,
                                key = keyPinned.addressOf(0),
                                keyLength = kCCKeySizeAES256.convert(),
                                iv = ivPinned.addressOf(0),
                                dataIn = if (input.isEmpty()) null else inputPinned.addressOf(0),
                                dataInLength = input.size.convert(),
                                dataOut = outputPinned.addressOf(0),
                                dataOutAvailable = output.size.convert(),
                                dataOutMoved = outLength.ptr,
                            )
                            if (status != kCCSuccess) {
                                throw IllegalStateException("AES-CBC operation failed")
                            }
                            outputSize = outLength.value.toInt()
                        }
                    }
                }
            }
        }
        return output.copyOf(outputSize)
    }
}
