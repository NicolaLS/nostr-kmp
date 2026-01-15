package io.github.nicolals.nostr.core.transport

/**
 * Result type for transport operations that may fail.
 *
 * Used by [RelayConnection] methods to indicate success or failure
 * without throwing exceptions.
 *
 * @param T The type of the success value.
 */
sealed class TransportResult<out T> {

    /**
     * The operation completed successfully.
     *
     * @property value The result value.
     */
    data class Success<T>(val value: T) : TransportResult<T>()

    /**
     * The operation failed.
     *
     * @property error The transport error that occurred.
     */
    data class Failure(val error: TransportError) : TransportResult<Nothing>()

    /**
     * Returns true if this is a [Success].
     */
    val isSuccess: Boolean
        get() = this is Success

    /**
     * Returns true if this is a [Failure].
     */
    val isFailure: Boolean
        get() = this is Failure

    companion object {
        /**
         * Creates a successful result with [Unit] value.
         */
        fun success(): TransportResult<Unit> = Success(Unit)

        /**
         * Creates a failure result with the given [error].
         */
        fun failure(error: TransportError): TransportResult<Nothing> = Failure(error)
    }
}
