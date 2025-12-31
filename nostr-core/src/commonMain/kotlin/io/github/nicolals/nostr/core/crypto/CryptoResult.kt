package io.github.nicolals.nostr.core.crypto

sealed class CryptoResult<out T> {
    data class Ok<T>(val value: T) : CryptoResult<T>()
    data class Err(val error: CryptoError) : CryptoResult<Nothing>()

    fun getOrNull(): T? = when (this) {
        is Ok -> value
        is Err -> null
    }

    fun errorOrNull(): CryptoError? = when (this) {
        is Ok -> null
        is Err -> error
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
