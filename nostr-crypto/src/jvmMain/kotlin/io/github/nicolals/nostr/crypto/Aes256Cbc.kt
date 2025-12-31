package io.github.nicolals.nostr.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object Aes256Cbc {
    private const val BLOCK_SIZE = 16
    private const val KEY_SIZE = 32
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

    actual fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "key must be 32 bytes" }
        require(iv.size == BLOCK_SIZE) { "iv must be 16 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    actual fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "key must be 32 bytes" }
        require(iv.size == BLOCK_SIZE) { "iv must be 16 bytes" }
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        require(ciphertext.size % BLOCK_SIZE == 0) { "ciphertext length must be multiple of 16" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }
}
