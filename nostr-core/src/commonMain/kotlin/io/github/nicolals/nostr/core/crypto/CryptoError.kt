package io.github.nicolals.nostr.core.crypto

sealed class CryptoError {
    data class InvalidInput(val name: String, val expected: String, val actual: Int) : CryptoError()
    data class InvalidPublicKey(val reason: String) : CryptoError()
    data class InvalidPrivateKey(val reason: String) : CryptoError()
    data class DecodeError(val format: String) : CryptoError()
    data class InitializationFailed(val library: String, val reason: String? = null) : CryptoError()
    data class DecryptionFailed(val reason: String? = null) : CryptoError()
    data class ProviderError(val operation: String, val reason: String? = null) : CryptoError()
    data class Unsupported(val operation: String) : CryptoError()
}
