package io.github.nicolals.nostr.nip42.protocol

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.protocol.ProtocolInput

/**
 * User intent inputs for NIP-42 authentication operations.
 *
 * These inputs represent user/application requests to perform
 * authentication operations. The NIP-42 reducer processes these
 * intents and produces appropriate effects and state changes.
 */
sealed interface Nip42Intent : ProtocolInput {

    /**
     * Request to authenticate with the relay using a signed auth event.
     *
     * The [authEvent] must be a kind 22242 event with proper "relay" and
     * "challenge" tags as defined by NIP-42. The challenge in the event
     * should match the challenge received from the relay.
     *
     * ## Prerequisites
     *
     * This intent should only be sent after receiving an AUTH challenge
     * from the relay (i.e., when [Nip42State.hasPendingChallenge] is true).
     * Sending without a pending challenge will be ignored.
     *
     * ## Response
     *
     * The relay will respond with an OK message indicating success or failure.
     * On success, [Nip42Signal.Authenticated] is emitted.
     * On failure, [Nip42Signal.AuthFailed] is emitted.
     *
     * @property authEvent The signed kind 22242 authentication event.
     */
    data class Authenticate(val authEvent: Event) : Nip42Intent
}
