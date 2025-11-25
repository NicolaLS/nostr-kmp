package nostr.runtime.coroutines

/** Default timeout applied to opening relay connections to avoid hanging in CONNECTING. */
internal const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 2_000

/** Default inactivity window before treating a connected relay as dead. */
internal const val DEFAULT_READ_TIMEOUT_MILLIS: Long = 15_000
