package io.github.nicolals.nostr.nip42.protocol

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.protocol.ModuleStateKey

/**
 * The phase of the NIP-42 authentication lifecycle.
 *
 * Auth follows this state machine:
 * ```
 * Unauthenticated → ChallengeReceived → AuthPending → Authenticated
 *                                     ↘ AuthFailed
 * ```
 *
 * On disconnect, auth state resets to Unauthenticated (auth is connection-scoped).
 */
enum class AuthPhase {
    /**
     * No authentication has been performed or requested.
     *
     * This is the initial state and the state after disconnect.
     */
    Unauthenticated,

    /**
     * An AUTH challenge has been received from the relay.
     *
     * The client can now send an AUTH response with a signed event.
     */
    ChallengeReceived,

    /**
     * An AUTH response has been sent, waiting for OK confirmation.
     */
    AuthPending,

    /**
     * Authentication was successful.
     *
     * The relay has accepted the AUTH event.
     */
    Authenticated,

    /**
     * Authentication failed.
     *
     * The relay rejected the AUTH event. The client may retry with
     * a new AUTH response if desired.
     */
    AuthFailed,
}

/**
 * NIP-42 authentication state for a relay connection.
 *
 * Auth is connection-scoped: it resets when the connection closes.
 * A new AUTH challenge may be received at any time, replacing any
 * previous challenge.
 *
 * @property phase Current authentication phase.
 * @property challenge The current challenge string from the relay, if any.
 * @property pendingAuthEventId Event ID of pending auth, for correlating OK.
 * @property pendingAuthPubkey Pubkey used for pending auth, for signal emission.
 * @property authenticatedPubkey The pubkey that was authenticated, if successful.
 * @property failureMessage The failure message from the relay, if auth failed.
 */
data class Nip42State(
    val phase: AuthPhase = AuthPhase.Unauthenticated,
    val challenge: String? = null,
    val pendingAuthEventId: EventId? = null,
    val pendingAuthPubkey: PublicKey? = null,
    val authenticatedPubkey: PublicKey? = null,
    val failureMessage: String? = null,
) {
    /**
     * Whether the connection is currently authenticated.
     */
    val isAuthenticated: Boolean
        get() = phase == AuthPhase.Authenticated

    /**
     * Whether a challenge is available for authentication.
     */
    val hasPendingChallenge: Boolean
        get() = challenge != null && phase == AuthPhase.ChallengeReceived
}

/**
 * Module state key for NIP-42 authentication state.
 */
object Nip42StateKey : ModuleStateKey<Nip42State> {
    override val id: String = "nip42.auth"
    override val default: Nip42State = Nip42State()
}
