package io.github.nicolals.nostr.core.nip

import io.github.nicolals.nostr.core.event.upgrade.EventUpgradeRegistry
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.upgrade.MessageUpgradeRegistry
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.protocol.OperationAdmission
import io.github.nicolals.nostr.core.protocol.ProtocolEngine
import io.github.nicolals.nostr.core.protocol.ProtocolReducer

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
    /** Stable namespaced identity. A registry accepts each ID exactly once. */
    val id: String

    /** Module IDs that must be installed before this module's reducers. */
    val requires: Set<String> get() = emptySet()

    /** Policies evaluated before any reducer handles an operation. */
    val admissionPolicies: List<OperationAdmission> get() = emptyList()

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
    nip.admissionPolicies.forEach(::admission)
    nip.protocolReducers.forEach(::register)
}
