package io.github.nicolals.nostr.nip42.protocol

import io.github.nicolals.nostr.core.protocol.ConnectionContext
import io.github.nicolals.nostr.core.transport.RelayUrl

/**
 * One challenge occurrence on one connection. Retain this token while signing and
 * return it with Authenticate; a newer occurrence invalidates it even if text repeats.
 */
class AuthChallenge internal constructor(
    val value: String,
    internal val connection: ConnectionContext,
) {
    val relayUrl: RelayUrl get() = connection.relayUrl

    override fun toString(): String = "AuthChallenge(relayUrl=$relayUrl)"
}

enum class AuthRejectionReason {
    NotConnected,
    StaleChallenge,
    InvalidEvent,
}
