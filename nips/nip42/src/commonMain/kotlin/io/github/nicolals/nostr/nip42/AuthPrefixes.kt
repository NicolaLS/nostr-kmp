package io.github.nicolals.nostr.nip42

/**
 * Machine-readable prefixes for OK and CLOSED messages related to authentication.
 *
 * These prefixes are used in the message field of OK and CLOSED responses
 * to indicate authentication-related rejections.
 */
object AuthPrefixes {
    /**
     * Prefix indicating the client has not performed AUTH and the relay requires it.
     *
     * Used when:
     * - A REQ is rejected because authentication is required (in CLOSED message)
     * - An EVENT is rejected because authentication is required (in OK message)
     *
     * Example: `"auth-required: we can't serve DMs to unauthenticated users"`
     */
    const val AUTH_REQUIRED = "auth-required:"

    /**
     * Prefix indicating the client has authenticated but is not authorized.
     *
     * Used when the authenticated pubkey is not allowed by the relay or is
     * exceeding its authorization level.
     *
     * Example: `"restricted: your pubkey is not whitelisted"`
     */
    const val RESTRICTED = "restricted:"
}

/**
 * Check if a message indicates authentication is required.
 */
fun String.isAuthRequired(): Boolean = trimStart().startsWith(AuthPrefixes.AUTH_REQUIRED)

/**
 * Check if a message indicates the authenticated user is restricted.
 */
fun String.isRestricted(): Boolean = trimStart().startsWith(AuthPrefixes.RESTRICTED)

/**
 * Extract the human-readable part of an auth-required message.
 * Returns null if the message doesn't have the auth-required prefix.
 */
fun String.authRequiredReason(): String? {
    val trimmed = trimStart()
    return if (trimmed.startsWith(AuthPrefixes.AUTH_REQUIRED)) {
        trimmed.removePrefix(AuthPrefixes.AUTH_REQUIRED).trimStart()
    } else null
}

/**
 * Extract the human-readable part of a restricted message.
 * Returns null if the message doesn't have the restricted prefix.
 */
fun String.restrictedReason(): String? {
    val trimmed = trimStart()
    return if (trimmed.startsWith(AuthPrefixes.RESTRICTED)) {
        trimmed.removePrefix(AuthPrefixes.RESTRICTED).trimStart()
    } else null
}
