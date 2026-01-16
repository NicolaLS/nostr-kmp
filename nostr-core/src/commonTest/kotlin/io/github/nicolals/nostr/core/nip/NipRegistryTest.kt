package io.github.nicolals.nostr.core.nip

import io.github.nicolals.nostr.core.codec.JsonValue
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.event.view.GenericEvent
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class NipRegistryTest {

    // Stub codec for testing - methods throw since tests don't use codec functionality
    private object StubCodec : NostrWireCodec {
        override fun decodeEvent(value: JsonValue): WireEvent = error("Not implemented")
        override fun encodeEvent(event: WireEvent): JsonValue = error("Not implemented")
        override fun decodeFilter(value: JsonValue): WireFilter = error("Not implemented")
        override fun encodeFilter(filter: WireFilter): JsonValue = error("Not implemented")
        override fun decodeClientMessage(value: JsonValue): RawClientMessage = error("Not implemented")
        override fun decodeRelayMessage(value: JsonValue): RawRelayMessage = error("Not implemented")
    }

    private fun testEvent(kind: Int) = Event(
        id = EventId("0".repeat(64).decodeHex()),
        pubkey = PublicKey.fromHex("0".repeat(64)),
        createdAt = UnixTimeSeconds(0),
        kind = EventKind(kind),
        tags = emptyList(),
        content = "test",
        sig = EventSig("0".repeat(128).decodeHex()),
    )

    @Test
    fun builderCreatesRegistryWithInstalledModules() {
        val registry = NipRegistry.Builder(StubCodec)
            .install(TestModule)
            .build()

        assertNotNull(registry.events)
        assertNotNull(registry.messages)
        assertNotNull(registry.engine)
        assertSame(StubCodec, registry.wireCodec)
    }

    @Test
    fun registryUpgradesEventsFromInstalledModules() {
        val registry = NipRegistry.Builder(StubCodec)
            .install(TestModule)
            .build()

        val view = registry.events.upgrade(testEvent(12345))
        assertIs<TestEventView>(view)
    }

    @Test
    fun registryFallsBackToGenericEventForUnknownKinds() {
        val registry = NipRegistry.Builder(StubCodec)
            .install(TestModule)
            .build()

        val view = registry.events.upgrade(testEvent(65000))
        assertIs<GenericEvent>(view)
    }

    // Test module - no context needed for this simple test
    private object TestModule : NipModule {
        override val eventUpgraders = listOf(TestEventUpgrader)
    }

    // Test event view
    private class TestEventView(override val event: Event) : EventView

    // Test upgrader
    private object TestEventUpgrader : EventUpgrader<TestEventView> {
        override val kinds = setOf(EventKind(12345))
        override val priority = 0
        override fun upgradeOrNull(event: Event): TestEventView? =
            if (event.kind == EventKind(12345)) TestEventView(event) else null
    }
}
