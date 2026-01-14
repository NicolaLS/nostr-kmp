package io.github.nicolals.nostr.nip42.message

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.toWireEvent
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.message.view.RelayMessageView

/**
 * NIP-42 AUTH token used for both client and relay messages.
 */
const val AUTH_TOKEN = "AUTH"

/**
 * NIP-42 RELAY AUTH message - relay sends authentication challenge.
 * Wire format: ["AUTH", <challenge-string>]
 *
 * Sent by relays to initiate authentication. The challenge is valid for the
 * duration of the connection or until another challenge is sent.
 */
data class AuthChallengeMessage(
    override val raw: RawRelayMessage,
    /** The challenge string to be signed by the client */
    val challenge: String,
) : RelayMessageView {
    companion object {
        const val TOKEN = AUTH_TOKEN
    }
}

/**
 * NIP-42 CLIENT AUTH message - client authenticates with signed event.
 * Wire format: ["AUTH", <signed-event-json>]
 *
 * Sent by clients in response to an AUTH challenge. The signed event must be
 * kind 22242 with "relay" and "challenge" tags.
 *
 * Relays respond to AUTH messages with an OK message.
 */
data class AuthClientMessage(
    override val raw: RawClientMessage,
    /** The wire-format auth event (permissive) */
    val wireEvent: WireEvent,
    /** The domain-typed auth event (validated), null if validation failed */
    val event: Event?,
) : ClientMessageView {
    companion object {
        const val TOKEN = AUTH_TOKEN

        /**
         * Create an AUTH client message from a signed Event.
         */
        fun create(event: Event, codec: NostrWireCodec): AuthClientMessage {
            val wireEvent = event.toWireEvent()
            val array = JsonArray(listOf(JsonString(TOKEN), codec.encodeEvent(wireEvent)))
            return AuthClientMessage(RawClientMessage(array), wireEvent, event)
        }
    }
}
