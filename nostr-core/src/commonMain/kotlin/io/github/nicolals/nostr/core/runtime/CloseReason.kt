package io.github.nicolals.nostr.core.runtime

/**
 * Parsed reason from a relay CLOSED message.
 */
sealed interface CloseReason {
    /**
     * The raw message from the relay.
     */
    val rawMessage: String

    /**
     * Relay requires AUTH for this subscription.
     *
     * @property message Human-readable details without the prefix.
     */
    data class AuthRequired(
        val message: String,
        override val rawMessage: String,
    ) : CloseReason

    /**
     * Relay rejected the subscription due to authorization restrictions.
     *
     * @property message Human-readable details without the prefix.
     */
    data class Restricted(
        val message: String,
        override val rawMessage: String,
    ) : CloseReason

    /**
     * Unrecognized or non-auth related close reason.
     */
    data class Other(
        override val rawMessage: String,
    ) : CloseReason

    companion object {
        /** Parses machine-readable CLOSED prefixes while preserving the relay text. */
        fun parse(rawMessage: String): CloseReason {
            val trimmed = rawMessage.trimStart()
            return when {
                trimmed.startsWith("auth-required:") ->
                    AuthRequired(trimmed.removePrefix("auth-required:").trimStart(), rawMessage)
                trimmed.startsWith("restricted:") ->
                    Restricted(trimmed.removePrefix("restricted:").trimStart(), rawMessage)
                else -> Other(rawMessage)
            }
        }
    }
}
