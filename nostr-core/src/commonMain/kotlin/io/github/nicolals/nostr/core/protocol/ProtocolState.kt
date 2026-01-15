package io.github.nicolals.nostr.core.protocol

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * The complete protocol state for a single relay connection.
 *
 * This is an immutable aggregate of all protocol state:
 * - [connection]: Current connection lifecycle state
 * - [subscriptions]: Active and recent subscriptions by ID
 * - [publishes]: Pending and recent publishes by event ID
 * - [moduleStates]: NIP module-specific state
 *
 * ## State Lifecycle
 *
 * The engine only manages state transitions according to protocol semantics.
 * State retention/eviction is a policy decision left to the runtime:
 * - Terminal subscription states (Closed, Interrupted) can be evicted
 * - Terminal publish states (Accepted, Rejected, Interrupted) can be evicted
 * - Eviction is done via explicit inputs (ForgetSubscription, ForgetPublish)
 *
 * ## Thread Safety
 *
 * [ProtocolState] is immutable and can be safely shared between threads.
 * State updates produce new instances.
 *
 * @property connection Connection lifecycle state.
 * @property subscriptions Subscription states keyed by subscription ID.
 * @property publishes Publish states keyed by event ID.
 * @property moduleStates NIP module-specific state.
 */
data class ProtocolState(
    val connection: ConnectionState = ConnectionState(),
    val subscriptions: Map<SubscriptionId, SubscriptionState> = emptyMap(),
    val publishes: Map<EventId, PublishState> = emptyMap(),
    val moduleStates: ModuleStateStore = ModuleStateStore(),
) {
    /**
     * Whether the connection is currently open.
     */
    val isConnected: Boolean
        get() = connection.isOpen

    /**
     * Gets the subscription state for the given ID, or null if not found.
     */
    fun subscription(id: SubscriptionId): SubscriptionState? = subscriptions[id]

    /**
     * Gets the publish state for the given event ID, or null if not found.
     */
    fun publish(eventId: EventId): PublishState? = publishes[eventId]

    /**
     * Returns a copy with the connection state updated.
     */
    fun withConnection(connection: ConnectionState): ProtocolState =
        copy(connection = connection)

    /**
     * Returns a copy with a subscription state added or updated.
     */
    fun withSubscription(state: SubscriptionState): ProtocolState =
        copy(subscriptions = subscriptions + (state.subscriptionId to state))

    /**
     * Returns a copy with a subscription removed.
     */
    fun withoutSubscription(id: SubscriptionId): ProtocolState =
        copy(subscriptions = subscriptions - id)

    /**
     * Returns a copy with a publish state added or updated.
     */
    fun withPublish(state: PublishState): ProtocolState =
        copy(publishes = publishes + (state.eventId to state))

    /**
     * Returns a copy with a publish removed.
     */
    fun withoutPublish(eventId: EventId): ProtocolState =
        copy(publishes = publishes - eventId)

    /**
     * Returns a copy with module states updated.
     */
    fun withModuleStates(moduleStates: ModuleStateStore): ProtocolState =
        copy(moduleStates = moduleStates)
}
