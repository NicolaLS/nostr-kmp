package io.github.nicolals.nostr.core.message.upgrade

import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.message.view.RelayMessageView
import io.github.nicolals.nostr.core.message.view.UnknownClientMessage
import io.github.nicolals.nostr.core.message.view.UnknownRelayMessage

/**
 * Registry for message upgraders that routes raw messages to typed views.
 *
 * Similar to [io.github.nicolals.nostr.core.event.upgrade.EventUpgradeRegistry] for events,
 * this registry collects upgraders from NIP modules and dispatches raw messages
 * to the appropriate upgrader based on the message token.
 *
 * When no upgrader can handle a message, it falls back to [UnknownClientMessage]
 * or [UnknownRelayMessage].
 */
class MessageUpgradeRegistry private constructor(
    private val clientByToken: Map<String, List<ClientMessageUpgrader<*>>>,
    private val relayByToken: Map<String, List<RelayMessageUpgrader<*>>>,
) {
    /**
     * Builder for constructing a [MessageUpgradeRegistry].
     */
    class Builder {
        private val clientList = mutableListOf<ClientMessageUpgrader<*>>()
        private val relayList = mutableListOf<RelayMessageUpgrader<*>>()

        /**
         * Register a client message upgrader.
         */
        fun registerClient(upgrader: ClientMessageUpgrader<*>) = apply {
            clientList += upgrader
        }

        /**
         * Register a relay message upgrader.
         */
        fun registerRelay(upgrader: RelayMessageUpgrader<*>) = apply {
            relayList += upgrader
        }

        /**
         * Build the registry.
         */
        fun build(): MessageUpgradeRegistry {
            val clientMap = buildClientTokenMap(clientList)
            val relayMap = buildRelayTokenMap(relayList)
            return MessageUpgradeRegistry(clientMap, relayMap)
        }

        private fun buildClientTokenMap(
            list: List<ClientMessageUpgrader<*>>
        ): Map<String, List<ClientMessageUpgrader<*>>> {
            val map = mutableMapOf<String, MutableList<ClientMessageUpgrader<*>>>()
            for (upgrader in list) {
                for (token in upgrader.tokens) {
                    map.getOrPut(token) { mutableListOf() }.add(upgrader)
                }
            }
            // Sort by priority descending (higher priority first)
            map.values.forEach { it.sortByDescending { u -> u.priority } }
            return map
        }

        private fun buildRelayTokenMap(
            list: List<RelayMessageUpgrader<*>>
        ): Map<String, List<RelayMessageUpgrader<*>>> {
            val map = mutableMapOf<String, MutableList<RelayMessageUpgrader<*>>>()
            for (upgrader in list) {
                for (token in upgrader.tokens) {
                    map.getOrPut(token) { mutableListOf() }.add(upgrader)
                }
            }
            // Sort by priority descending (higher priority first)
            map.values.forEach { it.sortByDescending { u -> u.priority } }
            return map
        }
    }

    /**
     * Upgrade a raw client message to a typed view.
     *
     * Iterates through registered upgraders for the message's token in priority order.
     * Returns [UnknownClientMessage] if no upgrader can handle the message.
     */
    fun upgradeClient(raw: RawClientMessage): ClientMessageView {
        val token = raw.tokenOrNull ?: return UnknownClientMessage(raw)

        val candidates = clientByToken[token].orEmpty()
        for (upgrader in candidates) {
            val view = upgrader.upgradeOrNull(raw, token)
            if (view != null) return view
        }
        return UnknownClientMessage(raw)
    }

    /**
     * Upgrade a raw relay message to a typed view.
     *
     * Iterates through registered upgraders for the message's token in priority order.
     * Returns [UnknownRelayMessage] if no upgrader can handle the message.
     */
    fun upgradeRelay(raw: RawRelayMessage): RelayMessageView {
        val token = raw.tokenOrNull ?: return UnknownRelayMessage(raw)

        val candidates = relayByToken[token].orEmpty()
        for (upgrader in candidates) {
            val view = upgrader.upgradeOrNull(raw, token)
            if (view != null) return view
        }
        return UnknownRelayMessage(raw)
    }
}
