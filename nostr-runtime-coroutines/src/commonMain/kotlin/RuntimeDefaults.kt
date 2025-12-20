package nostr.runtime.coroutines

/** Default timeout applied to opening relay connections to avoid hanging in CONNECTING. */
internal const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 8_000

/** Default inactivity window before treating a connected relay as dead (0 disables). */
internal const val DEFAULT_READ_TIMEOUT_MILLIS: Long = 0

/** Maximum time allowed for a disconnect/close sequence before forcing cleanup. */
internal const val DEFAULT_CLOSE_TIMEOUT_MILLIS: Long = 2_000

/** Default deadline for transport-level write confirmations (0 disables). */
internal const val DEFAULT_WRITE_CONFIRMATION_TIMEOUT_MILLIS: Long = 10_000
