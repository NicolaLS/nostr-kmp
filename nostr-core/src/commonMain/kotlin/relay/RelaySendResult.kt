package nostr.core.relay

/** Normalized result of delivering a frame to the relay transport. */
sealed interface RelaySendResult {
    /** Frame accepted for delivery. */
    data object Accepted : RelaySendResult

    /** Frame rejected because the transport is not currently connected. */
    data object NotConnected : RelaySendResult

    /** Frame rejected synchronously with a specific failure. */
    data class Failed(val cause: Throwable) : RelaySendResult
}
