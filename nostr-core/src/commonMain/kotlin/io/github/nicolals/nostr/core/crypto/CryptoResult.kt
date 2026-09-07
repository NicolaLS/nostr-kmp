package io.github.nicolals.nostr.core.crypto

sealed class CryptoResult<out T> {
    data class Ok<T>(val value: T) : CryptoResult<T>() {
        // Successful crypto operations can return private keys or decrypted plaintext.
        override fun toString(): String = "Ok([redacted])"
    }
    data class Err(val error: CryptoError) : CryptoResult<Nothing>()

    fun getOrNull(): T? = when (this) {
        is Ok -> value
        is Err -> null
    }

    fun errorOrNull(): CryptoError? = when (this) {
        is Ok -> null
        is Err -> error
    }

    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw CryptoException(error)
    }

    inline fun <R> map(transform: (T) -> R): CryptoResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> Err(error)
    }

    inline fun <R> flatMap(transform: (T) -> CryptoResult<R>): CryptoResult<R> = when (this) {
        is Ok -> transform(value)
        is Err -> Err(error)
    }
}

class CryptoException(val error: CryptoError) : IllegalStateException("Crypto operation failed: $error")
