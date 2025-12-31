package io.github.nicolals.nostr.crypto

internal expect object Aes256Cbc {
    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray
}
