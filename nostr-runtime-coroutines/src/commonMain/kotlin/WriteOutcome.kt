package nostr.runtime.coroutines

/**
 * Outcome of a write operation to the relay transport.
 *
 * This represents the actual result of writing a frame to the underlying WebSocket,
 * not just acceptance into a queue. Use this to detect stale/dead connections
 * that appear connected but can't actually deliver messages.
 */
sealed interface WriteOutcome {
    /** Frame was successfully written to the transport. */
    data object Success : WriteOutcome

    /** Write operation timed out - connection is likely stale/dead. */
    data object Timeout : WriteOutcome

    /** Write failed with a specific error. */
    data class Failed(val cause: Throwable) : WriteOutcome

    /** Check if this outcome represents a successful write. */
    fun isSuccess(): Boolean = this is Success
}

/**
 * Returns the outcome if successful, or throws the failure cause.
 */
fun WriteOutcome.getOrThrow(): Unit = when (this) {
    WriteOutcome.Success -> Unit
    WriteOutcome.Timeout -> throw WriteTimeoutException("Write timed out - connection likely stale")
    is WriteOutcome.Failed -> throw cause
}

/**
 * Exception thrown when a write operation times out.
 */
class WriteTimeoutException(message: String) : Exception(message)
