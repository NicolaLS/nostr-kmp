package io.github.nicolals.nostr.core.nip

import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.event.upgrade.EventUpgradeRegistry
import io.github.nicolals.nostr.core.message.upgrade.MessageUpgradeRegistry
import io.github.nicolals.nostr.core.protocol.ProtocolEngine

/**
 * Bundles event registry, message registry, protocol engine, and wire codec for unified usage.
 *
 * This solves the common problem of needing to install NIP modules into three separate
 * registries. With `NipRegistry`, you install once and all registries are updated.
 *
 * The registry also holds the [wireCodec] used for encoding/decoding messages, making it
 * the single dependency needed by runtime implementations.
 *
 * ## Usage
 *
 * ```kotlin
 * val codec = KotlinxNostrJsonCodec()
 * val ctx = SimpleNipModuleContext(codec, NostrCrypto)
 *
 * val registry = NipRegistry.Builder(codec)
 *     .install(Nip01Module(ctx))
 *     .install(Nip42Module(ctx))
 *     .build()
 *
 * // Access individual registries
 * val eventView = registry.events.upgrade(event)
 * val messageView = registry.messages.upgradeRelay(rawMessage)
 * val result = registry.engine.process(state, input)
 *
 * // Access codec for encoding/decoding
 * val json = registry.wireCodec.encodeClientMessage(clientMessage)
 * ```
 *
 * ## Note on Module Order
 *
 * Installation order matters for protocol reducers. Install dependencies first
 * (e.g., Nip01 before Nip42, since Nip42's reducer depends on Nip01's OK message handling).
 */
class NipRegistry(
    /** Registry for upgrading events to typed views */
    val events: EventUpgradeRegistry,

    /** Registry for upgrading raw messages to typed views */
    val messages: MessageUpgradeRegistry,

    /** Protocol engine for FSM processing */
    val engine: ProtocolEngine,

    /** Wire codec for encoding/decoding Nostr messages */
    val wireCodec: NostrWireCodec,
) {
    /**
     * Builder for creating [NipRegistry] instances.
     *
     * @param wireCodec The codec used for encoding/decoding Nostr wire messages.
     */
    class Builder(private val wireCodec: NostrWireCodec) {
        private val eventBuilder = EventUpgradeRegistry.Builder()
        private val messageBuilder = MessageUpgradeRegistry.Builder()
        private val engineBuilder = ProtocolEngine.Builder()

        /**
         * Installs a NIP module into all registries.
         *
         * This registers the module's event upgraders, message upgraders,
         * and protocol reducers in one call.
         */
        fun install(module: NipModule) = apply {
            eventBuilder.install(module)
            messageBuilder.install(module)
            engineBuilder.install(module)
        }

        /**
         * Builds the [NipRegistry] with all installed modules.
         */
        fun build() = NipRegistry(
            events = eventBuilder.build(),
            messages = messageBuilder.build(),
            engine = engineBuilder.build(),
            wireCodec = wireCodec,
        )
    }
}
