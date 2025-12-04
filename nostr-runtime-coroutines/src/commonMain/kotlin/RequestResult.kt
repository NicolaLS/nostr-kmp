package nostr.runtime.coroutines

/**
 * Sealed result type for request-response operations.
 * Provides type-safe handling of all possible outcomes.
 */
sealed interface RequestResult<out T> {
    /** Operation completed successfully with a response. */
    data class Success<T>(val value: T) : RequestResult<T>

    /**
     * Connection could not be established within the time budget.
     * @param attempts Number of connection attempts made
     * @param lastError Description of the last failure
     */
    data class ConnectionFailed(
        val attempts: Int,
        val lastError: String?
    ) : RequestResult<Nothing>

    /**
     * Connected but no response received within the timeout period.
     * @param elapsedMillis Total time spent waiting
     */
    data class Timeout(
        val elapsedMillis: Long
    ) : RequestResult<Nothing>
}

/**
 * Fold over a RequestResult to handle all cases.
 */
inline fun <T, R> RequestResult<T>.fold(
    onSuccess: (T) -> R,
    onConnectionFailed: (RequestResult.ConnectionFailed) -> R,
    onTimeout: (RequestResult.Timeout) -> R
): R = when (this) {
    is RequestResult.Success -> onSuccess(value)
    is RequestResult.ConnectionFailed -> onConnectionFailed(this)
    is RequestResult.Timeout -> onTimeout(this)
}

/**
 * Returns the value if Success, null otherwise.
 */
fun <T> RequestResult<T>.getOrNull(): T? = (this as? RequestResult.Success)?.value

/**
 * Returns true if this is a Success result.
 */
fun <T> RequestResult<T>.isSuccess(): Boolean = this is RequestResult.Success

/**
 * Returns true if this is not a Success result.
 */
fun <T> RequestResult<T>.isFailure(): Boolean = this !is RequestResult.Success

/**
 * Returns the value if Success, throws IllegalStateException otherwise.
 */
fun <T> RequestResult<T>.getOrThrow(): T = when (this) {
    is RequestResult.Success -> value
    is RequestResult.ConnectionFailed -> throw IllegalStateException("Connection failed: $lastError")
    is RequestResult.Timeout -> throw IllegalStateException("Request timed out after ${elapsedMillis}ms")
}

/**
 * Maps the success value using the given transform function.
 */
inline fun <T, R> RequestResult<T>.map(transform: (T) -> R): RequestResult<R> = when (this) {
    is RequestResult.Success -> RequestResult.Success(transform(value))
    is RequestResult.ConnectionFailed -> this
    is RequestResult.Timeout -> this
}
