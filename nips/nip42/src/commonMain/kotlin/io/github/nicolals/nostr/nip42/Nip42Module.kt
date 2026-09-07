package io.github.nicolals.nostr.nip42

import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.protocol.ProtocolReducer
import io.github.nicolals.nostr.nip42.event.AuthEventUpgrader
import io.github.nicolals.nostr.nip42.message.Nip42ClientMessageUpgrader
import io.github.nicolals.nostr.nip42.message.Nip42RelayMessageUpgrader
import io.github.nicolals.nostr.nip42.protocol.Nip42Reducer

/**
 * NIP-42 module providing client authentication to relays.
 *
 * This module provides:
 * - Relay message view for AUTH challenge: [io.github.nicolals.nostr.nip42.message.AuthChallengeMessage]
 * - Client message view for AUTH response: [io.github.nicolals.nostr.nip42.message.AuthClientMessage]
 * - Event view for auth events (kind 22242): [io.github.nicolals.nostr.nip42.event.AuthEvent]
 *
 * ## Usage
 *
 * Install the module to handle AUTH protocol messages:
 * ```kotlin
 * val registry = NipRegistry.Builder(codec)
 *     .install(Nip01Module(codec))
 *     .install(Nip42Module(codec))
 *     .build()
 * ```
 *
 * Create an auth response event:
 * ```kotlin
 * val template = AuthEvent.template(
 *     relayUrl = "wss://relay.example.com/",
 *     challenge = challengeFromRelay,
 *     createdAt = UnixTimeSeconds.now(),
 * )
 * val authEvent = signer.sign(template).getOrThrow()
 * val authMessage = AuthClientMessage.create(authEvent.event, codec)
 * ```
 *
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/42.md">NIP-42 Specification</a>
 */
class Nip42Module(
    codec: NostrWireCodec,
) : NipModule {
    override val id: String = "nip42"
    override val requires: Set<String> = setOf("nip01")


    override val eventUpgraders: List<EventUpgrader<out EventView>> =
        listOf(AuthEventUpgrader())

    override val clientMessageUpgraders: List<ClientMessageUpgrader<*>> =
        listOf(Nip42ClientMessageUpgrader(codec))

    override val relayMessageUpgraders: List<RelayMessageUpgrader<*>> =
        listOf(Nip42RelayMessageUpgrader())

    override val protocolReducers: List<ProtocolReducer> =
        listOf(Nip42Reducer(codec))
}
