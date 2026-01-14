package io.github.nicolals.nostr.core.message.upgrade

import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.message.view.RelayMessageView

/**
 * Upgrades raw client messages to typed views.
 *
 * Similar to [io.github.nicolals.nostr.core.event.upgrade.EventUpgrader] for events,
 * this interface allows NIP modules to register message interpreters that convert
 * raw wire messages into typed domain objects.
 *
 * @param V The typed view type that this upgrader produces (must extend [ClientMessageView])
 */
interface ClientMessageUpgrader<out V : ClientMessageView> {
    /**
     * The message tokens this upgrader handles (for fast routing).
     * Examples: setOf("EVENT", "REQ", "CLOSE") for NIP-01 client messages.
     */
    val tokens: Set<String>

    /**
     * Higher priority wins when multiple upgraders match the same token.
     * Default is 0.
     */
    val priority: Int get() = 0

    /**
     * Attempt to upgrade a raw message to a typed view.
     *
     * @param raw The raw message to upgrade
     * @param token The already-extracted message token (avoids redundant extraction)
     * @return The typed view if this upgrader can handle the message, null otherwise
     */
    fun upgradeOrNull(raw: RawClientMessage, token: String): V?
}

/**
 * Upgrades raw relay messages to typed views.
 *
 * Similar to [io.github.nicolals.nostr.core.event.upgrade.EventUpgrader] for events,
 * this interface allows NIP modules to register message interpreters that convert
 * raw wire messages into typed domain objects.
 *
 * @param V The typed view type that this upgrader produces (must extend [RelayMessageView])
 */
interface RelayMessageUpgrader<out V : RelayMessageView> {
    /**
     * The message tokens this upgrader handles (for fast routing).
     * Examples: setOf("EVENT", "OK", "EOSE", "CLOSED", "NOTICE") for NIP-01 relay messages.
     */
    val tokens: Set<String>

    /**
     * Higher priority wins when multiple upgraders match the same token.
     * Default is 0.
     */
    val priority: Int get() = 0

    /**
     * Attempt to upgrade a raw message to a typed view.
     *
     * @param raw The raw message to upgrade
     * @param token The already-extracted message token (avoids redundant extraction)
     * @return The typed view if this upgrader can handle the message, null otherwise
     */
    fun upgradeOrNull(raw: RawRelayMessage, token: String): V?
}
