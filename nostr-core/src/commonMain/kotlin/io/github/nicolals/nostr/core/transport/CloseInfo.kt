package io.github.nicolals.nostr.core.transport

/**
 * Information about a WebSocket close event.
 *
 * @property code The WebSocket close code (see RFC 6455 section 7.4).
 * @property reason Optional human-readable close reason.
 * @property initiatedByRemote True if the remote peer initiated the close.
 */
data class CloseInfo(
    val code: Int,
    val reason: String?,
    val initiatedByRemote: Boolean,
) {
    companion object {
        /** Normal closure; the connection successfully completed. */
        const val NORMAL_CLOSURE = 1000

        /** Connection was closed abnormally (no close frame received). */
        const val ABNORMAL_CLOSURE = 1006
    }
}
