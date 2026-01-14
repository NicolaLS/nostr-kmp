package io.github.nicolals.nostr.nip42

import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.nip42.event.AuthEventUpgrader
import io.github.nicolals.nostr.nip42.message.Nip42ClientMessageUpgrader
import io.github.nicolals.nostr.nip42.message.Nip42RelayMessageUpgrader

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
 * val messageRegistry = MessageUpgradeRegistry.Builder()
 *     .install(Nip01Module(ctx))
 *     .install(Nip42Module(ctx))
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
 * val authEvent = signer.sign(template)
 * val authMessage = AuthClientMessage.create(authEvent.event, codec)
 * ```
 *
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/42.md">NIP-42 Specification</a>
 */
class Nip42Module(
    private val ctx: NipModuleContext,
) : NipModule {

    override val eventUpgraders: List<EventUpgrader<out EventView>> =
        listOf(AuthEventUpgrader())

    override val clientMessageUpgraders: List<ClientMessageUpgrader<*>> =
        listOf(Nip42ClientMessageUpgrader(ctx.wireCodec))

    override val relayMessageUpgraders: List<RelayMessageUpgrader<*>> =
        listOf(Nip42RelayMessageUpgrader())
}
