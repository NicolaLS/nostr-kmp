package io.github.nicolals.nostr.nip42.protocol

import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.protocol.ProtocolSignal

/**
 * Signals emitted by the NIP-42 reducer.
 *
 * These signals notify consumers about authentication lifecycle events.
 * The runtime surfaces these via flows or callbacks.
 */
sealed interface Nip42Signal : ProtocolSignal {

    /**
     * An AUTH challenge was received from the relay.
     *
     * The client should create a signed kind 22242 event with the
     * challenge and send it via [Nip42Intent.Authenticate].
     *
     * @property challenge The challenge string to sign.
     */
    data class ChallengeReceived(val challenge: String) : Nip42Signal

    /**
     * Authentication was sent and is pending confirmation.
     *
     * @property pubkey The public key used for authentication.
     */
    data class AuthPending(val pubkey: PublicKey) : Nip42Signal

    /**
     * Authentication was successful.
     *
     * The connection is now authenticated with the given pubkey.
     *
     * @property pubkey The public key that was authenticated.
     * @property message The message from the relay's OK response.
     */
    data class Authenticated(
        val pubkey: PublicKey,
        val message: String,
    ) : Nip42Signal

    /**
     * Authentication failed.
     *
     * The relay rejected the AUTH event. Check [message] for the reason.
     * The client may retry with a new AUTH event if desired.
     *
     * @property message The rejection message from the relay.
     */
    data class AuthFailed(val message: String) : Nip42Signal

    /**
     * Authentication was reset due to disconnect.
     *
     * Auth is connection-scoped, so it resets when the connection closes.
     * The client will need to re-authenticate after reconnecting.
     */
    data object AuthReset : Nip42Signal
}
