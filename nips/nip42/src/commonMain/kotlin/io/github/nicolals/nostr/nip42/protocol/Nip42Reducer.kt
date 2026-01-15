package io.github.nicolals.nostr.nip42.protocol

import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.protocol.InboundMessage
import io.github.nicolals.nostr.core.protocol.ProtocolEffect
import io.github.nicolals.nostr.core.protocol.ProtocolInput
import io.github.nicolals.nostr.core.protocol.ProtocolReducer
import io.github.nicolals.nostr.core.protocol.ProtocolState
import io.github.nicolals.nostr.core.protocol.ReduceResult
import io.github.nicolals.nostr.core.protocol.TransportInput
import io.github.nicolals.nostr.nip01.message.OkMessage
import io.github.nicolals.nostr.nip42.message.AuthChallengeMessage
import io.github.nicolals.nostr.nip42.message.AuthClientMessage

/**
 * NIP-42 protocol reducer implementing authentication semantics.
 *
 * This reducer handles:
 * - AUTH challenge messages from the relay
 * - Authenticate intent (sending AUTH response)
 * - OK messages correlating to auth events
 * - Auth state reset on disconnect
 *
 * ## Protocol Semantics
 *
 * The reducer encodes NIP-42 protocol facts:
 * - AUTH challenge can arrive at any time, replacing any previous challenge
 * - Auth response requires a pending challenge
 * - OK messages are correlated to auth by event ID
 * - Auth state is connection-scoped (resets on disconnect)
 *
 * ## Reducer Ordering
 *
 * This reducer should be registered AFTER the NIP-01 reducer. OK messages
 * for regular publishes are handled by NIP-01; OK messages for auth events
 * are handled by NIP-42 (correlated by pending auth event ID).
 *
 * @param wireCodec Codec for creating client messages.
 */
class Nip42Reducer(
    private val wireCodec: NostrWireCodec,
) : ProtocolReducer {

    override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
        return when (input) {
            // Transport lifecycle - reset auth on disconnect
            is TransportInput.Closed -> handleDisconnect(state)
            is TransportInput.Failed -> handleDisconnect(state)

            // User intents
            is Nip42Intent.Authenticate -> handleAuthenticate(state, input)

            // Inbound messages
            is InboundMessage -> handleInboundMessage(state, input.message)

            else -> null
        }
    }

    // ========== Transport Lifecycle ==========

    private fun handleDisconnect(state: ProtocolState): ReduceResult? {
        val authState = state.moduleStates[Nip42StateKey]

        // Only emit reset signal if we had some auth state
        if (authState.phase == AuthPhase.Unauthenticated && authState.challenge == null) {
            return null // No auth state to reset
        }

        val newAuthState = Nip42State() // Reset to default
        val newModuleStates = state.moduleStates.set(Nip42StateKey, newAuthState)
        val newState = state.withModuleStates(newModuleStates)

        return ReduceResult(
            state = newState,
            signals = listOf(Nip42Signal.AuthReset),
        )
    }

    // ========== User Intents ==========

    private fun handleAuthenticate(
        state: ProtocolState,
        intent: Nip42Intent.Authenticate,
    ): ReduceResult {
        if (!state.isConnected) {
            return ReduceResult.stateOnly(state)
        }

        val authState = state.moduleStates[Nip42StateKey]

        // Must have a pending challenge to authenticate
        if (authState.challenge == null) {
            return ReduceResult.stateOnly(state)
        }

        val authEvent = intent.authEvent
        val pubkey = authEvent.pubkey

        val newAuthState = authState.copy(
            phase = AuthPhase.AuthPending,
            pendingAuthEventId = authEvent.id,
            pendingAuthPubkey = pubkey,
        )

        val authMessage = AuthClientMessage.create(authEvent, wireCodec)
        val newModuleStates = state.moduleStates.set(Nip42StateKey, newAuthState)
        val newState = state.withModuleStates(newModuleStates)

        return ReduceResult(
            state = newState,
            effects = listOf(ProtocolEffect.Send(authMessage)),
            signals = listOf(Nip42Signal.AuthPending(pubkey)),
        )
    }

    // ========== Inbound Messages ==========

    private fun handleInboundMessage(
        state: ProtocolState,
        message: io.github.nicolals.nostr.core.message.view.RelayMessageView,
    ): ReduceResult? {
        return when (message) {
            is AuthChallengeMessage -> handleAuthChallenge(state, message)
            is OkMessage -> handleOk(state, message)
            else -> null
        }
    }

    private fun handleAuthChallenge(
        state: ProtocolState,
        message: AuthChallengeMessage,
    ): ReduceResult {
        val challenge = message.challenge
        val authState = state.moduleStates[Nip42StateKey]

        // New challenge replaces any previous challenge and resets auth state
        // but preserves authenticated state if already authenticated
        val newPhase = if (authState.phase == AuthPhase.Authenticated) {
            // Already authenticated, new challenge doesn't change that
            // (relay might send challenges periodically)
            AuthPhase.Authenticated
        } else {
            AuthPhase.ChallengeReceived
        }

        val newAuthState = authState.copy(
            phase = newPhase,
            challenge = challenge,
            pendingAuthEventId = null, // Clear any pending auth
            failureMessage = null, // Clear any previous failure
        )

        val newModuleStates = state.moduleStates.set(Nip42StateKey, newAuthState)
        val newState = state.withModuleStates(newModuleStates)

        // Only signal if we're not already authenticated
        val signals = if (authState.phase != AuthPhase.Authenticated) {
            listOf(Nip42Signal.ChallengeReceived(challenge))
        } else {
            emptyList()
        }

        return ReduceResult(
            state = newState,
            signals = signals,
        )
    }

    private fun handleOk(
        state: ProtocolState,
        message: OkMessage,
    ): ReduceResult? {
        val eventId = message.eventId ?: return null
        val authState = state.moduleStates[Nip42StateKey]

        // Only handle OK if it matches our pending auth event
        if (authState.pendingAuthEventId != eventId) {
            return null // Not for us - let other reducers handle it
        }

        // This OK is for our auth event
        val pendingPubkey = authState.pendingAuthPubkey
            ?: return null // Shouldn't happen, but be safe

        val newAuthState = if (message.accepted) {
            authState.copy(
                phase = AuthPhase.Authenticated,
                pendingAuthEventId = null,
                pendingAuthPubkey = null,
                authenticatedPubkey = pendingPubkey,
                failureMessage = null,
            )
        } else {
            authState.copy(
                phase = AuthPhase.AuthFailed,
                pendingAuthEventId = null,
                pendingAuthPubkey = null,
                failureMessage = message.message,
            )
        }

        val newModuleStates = state.moduleStates.set(Nip42StateKey, newAuthState)
        val newState = state.withModuleStates(newModuleStates)

        val signal = if (message.accepted) {
            Nip42Signal.Authenticated(pubkey = pendingPubkey, message = message.message)
        } else {
            Nip42Signal.AuthFailed(message.message)
        }

        return ReduceResult(
            state = newState,
            signals = listOf(signal),
        )
    }
}
