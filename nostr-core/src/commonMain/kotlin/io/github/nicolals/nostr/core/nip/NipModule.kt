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
import io.github.nicolals.nostr.core.protocol.ProtocolEngine
import io.github.nicolals.nostr.core.protocol.ProtocolReducer

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
 * Simple implementation of [NipModuleContext] bundling a codec and crypto primitives.
 *
 * @param codec A codec that implements both [NostrWireCodec] and [JsonCodec].
 *   Typically [io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec].
 * @param crypto Cryptographic primitives implementation.
 *   Typically [io.github.nicolals.nostr.crypto.NostrCrypto].
 */
class SimpleNipModuleContext(
    private val codec: NostrWireCodec,
    override val crypto: CryptoPrimitives,
) : NipModuleContext {
    override val jsonCodec: JsonCodec
        get() = codec as? JsonCodec
            ?: error("wireCodec must implement JsonCodec")

    override val wireCodec: NostrWireCodec
        get() = codec
}

/**
 * Bundle event upgraders, message upgraders, and protocol reducers for a NIP
 * so installation is explicit and consistent.
 *
 * Each NIP module can provide:
 * - Event upgraders for interpreting specific event kinds
 * - Client message upgraders for interpreting client-to-relay messages
 * - Relay message upgraders for interpreting relay-to-client messages
 * - Protocol reducers for handling protocol lifecycle semantics
 */
interface NipModule {
    /** Event upgraders provided by this NIP module */
    val eventUpgraders: List<EventUpgrader<out EventView>> get() = emptyList()

    /** Client message upgraders provided by this NIP module */
    val clientMessageUpgraders: List<ClientMessageUpgrader<*>> get() = emptyList()

    /** Relay message upgraders provided by this NIP module */
    val relayMessageUpgraders: List<RelayMessageUpgrader<*>> get() = emptyList()

    /** Protocol reducers provided by this NIP module */
    val protocolReducers: List<ProtocolReducer> get() = emptyList()
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

/**
 * Install all protocol reducers from a NIP module into the engine builder.
 */
fun ProtocolEngine.Builder.install(nip: NipModule) = apply {
    nip.protocolReducers.forEach(::register)
}
