package io.github.nicolals.nostr.core.nip

import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.event.upgrade.EventUpgradeRegistry
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.upgrade.MessageUpgradeRegistry
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.message.view.RelayMessageView

/**
 * Context providing shared services for NIP modules.
 */
interface NipModuleContext {
    /** Basic JSON codec for parsing/stringifying JSON values */
    val jsonCodec: JsonCodec

    /** Nostr wire codec for encoding/decoding events and filters */
    val wireCodec: NostrWireCodec

    /** Cryptographic primitives */
    val crypto: CryptoPrimitives
}

/**
 * Bundle event upgraders and message upgraders for a NIP so installation is explicit and consistent.
 *
 * Each NIP module can provide:
 * - Event upgraders for interpreting specific event kinds
 * - Client message upgraders for interpreting client-to-relay messages
 * - Relay message upgraders for interpreting relay-to-client messages
 */
interface NipModule {
    /** Event upgraders provided by this NIP module */
    val eventUpgraders: List<EventUpgrader<out EventView>> get() = emptyList()

    /** Client message upgraders provided by this NIP module */
    val clientMessageUpgraders: List<ClientMessageUpgrader<*>> get() = emptyList()

    /** Relay message upgraders provided by this NIP module */
    val relayMessageUpgraders: List<RelayMessageUpgrader<*>> get() = emptyList()
}

/**
 * Install all event upgraders from a NIP module into the registry.
 */
fun EventUpgradeRegistry.Builder.install(nip: NipModule) = apply {
    nip.eventUpgraders.forEach(::register)
}

/**
 * Install all message upgraders from a NIP module into the registry.
 */
fun MessageUpgradeRegistry.Builder.install(nip: NipModule) = apply {
    nip.clientMessageUpgraders.forEach(::registerClient)
    nip.relayMessageUpgraders.forEach(::registerRelay)
}
