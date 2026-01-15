package io.github.nicolals.nostr.nip01.protocol

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonBoolean
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.JsonValue
import io.github.nicolals.nostr.core.codec.NostrWireCodec
import io.github.nicolals.nostr.core.codec.wire.WireEvent
import io.github.nicolals.nostr.core.codec.wire.WireFilter
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.protocol.ConnectionPhase
import io.github.nicolals.nostr.core.protocol.ConnectionState
import io.github.nicolals.nostr.core.protocol.CoreSignal
import io.github.nicolals.nostr.core.protocol.InboundMessage
import io.github.nicolals.nostr.core.protocol.ProtocolEffect
import io.github.nicolals.nostr.core.protocol.ProtocolState
import io.github.nicolals.nostr.core.protocol.PublishState
import io.github.nicolals.nostr.core.protocol.PublishStatus
import io.github.nicolals.nostr.core.protocol.SubscriptionPhase
import io.github.nicolals.nostr.core.protocol.SubscriptionState
import io.github.nicolals.nostr.core.protocol.TransportInput
import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.TransportError
import io.github.nicolals.nostr.nip01.message.ClosedMessage
import io.github.nicolals.nostr.nip01.message.EoseMessage
import io.github.nicolals.nostr.nip01.message.EventClientMessage
import io.github.nicolals.nostr.nip01.message.NoticeMessage
import io.github.nicolals.nostr.nip01.message.OkMessage
import io.github.nicolals.nostr.nip01.message.RelayEventMessage
import io.github.nicolals.nostr.nip01.message.ReqClientMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip01ReducerTest {

    // ===== Test Fixtures =====

    private val stubCodec = object : NostrWireCodec {
        override fun decodeEvent(value: JsonValue) = WireEvent("", "", 0, 0, emptyList(), "", "")
        override fun encodeEvent(event: WireEvent) = JsonArray(emptyList())
        override fun decodeFilter(value: JsonValue) = WireFilter()
        override fun encodeFilter(filter: WireFilter) = JsonArray(emptyList())
        override fun decodeClientMessage(value: JsonValue) = RawClientMessage(JsonArray(emptyList()))
        override fun decodeRelayMessage(value: JsonValue) = RawRelayMessage(JsonArray(emptyList()))
    }

    private val reducer = Nip01Reducer(stubCodec)

    private val testSubId = SubscriptionId("test-sub")
    private val testSubId2 = SubscriptionId("test-sub-2")

    private val testEventId = EventId.fromHex("a".repeat(64))
    private val testEventId2 = EventId.fromHex("b".repeat(64))

    private val testPubkey = PublicKey.fromHex("c".repeat(64))
    private val testSig = EventSig.fromHex("d".repeat(128))

    private val testEvent = Event(
        id = testEventId,
        pubkey = testPubkey,
        createdAt = UnixTimeSeconds(1700000000),
        kind = EventKind(1),
        tags = emptyList(),
        content = "test content",
        sig = testSig,
    )

    private val testFilters = listOf(Filter(kinds = listOf(EventKind(1))))

    private fun connectedState() = ProtocolState(
        connection = ConnectionState(ConnectionPhase.Open)
    )

    // ===== Transport Lifecycle Tests =====

    @Test
    fun transportOpenedTransitionsToOpen() {
        val state = ProtocolState()
        val result = reducer.reduce(state, TransportInput.Opened)

        assertNotNull(result)
        assertEquals(ConnectionPhase.Open, result.state.connection.phase)
        assertTrue(result.signals.any { it is CoreSignal.ConnectionOpened })
    }

    @Test
    fun transportClosedTransitionsToDisconnected() {
        val state = connectedState()
        val closeInfo = CloseInfo(CloseInfo.NORMAL_CLOSURE, "test close", false)
        val result = reducer.reduce(state, TransportInput.Closed(closeInfo))

        assertNotNull(result)
        assertEquals(ConnectionPhase.Disconnected, result.state.connection.phase)
        val signal = result.signals.filterIsInstance<CoreSignal.ConnectionClosed>().firstOrNull()
        assertNotNull(signal)
        assertEquals(closeInfo, signal.info)
    }

    @Test
    fun transportFailedTransitionsToDisconnected() {
        val state = connectedState()
        val error = TransportError.IoError("connection failed")
        val result = reducer.reduce(state, TransportInput.Failed(error))

        assertNotNull(result)
        assertEquals(ConnectionPhase.Disconnected, result.state.connection.phase)
        val signal = result.signals.filterIsInstance<CoreSignal.ConnectionFailed>().firstOrNull()
        assertNotNull(signal)
        assertEquals(error, signal.error)
    }

    // ===== Subscription Tests =====

    @Test
    fun subscribeWhenConnectedCreatesSubscriptionState() {
        val state = connectedState()
        val intent = Nip01Intent.Subscribe(testSubId, testFilters)
        val result = reducer.reduce(state, intent)

        assertNotNull(result)
        val sub = result.state.subscription(testSubId)
        assertNotNull(sub)
        assertEquals(testSubId, sub.subscriptionId)
        assertEquals(1, sub.generation)
        assertEquals(SubscriptionPhase.CatchingUp, sub.phase)
        assertEquals(testFilters, sub.filters)

        // Should send REQ message
        assertTrue(result.effects.any { it is ProtocolEffect.Send && it.message is ReqClientMessage })

        // Should emit SubscriptionStarted signal
        val signal = result.signals.filterIsInstance<Nip01Signal.SubscriptionStarted>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testSubId, signal.subscriptionId)
        assertEquals(1, signal.generation)
    }

    @Test
    fun subscribeWhenNotConnectedDoesNothing() {
        val state = ProtocolState() // disconnected
        val intent = Nip01Intent.Subscribe(testSubId, testFilters)
        val result = reducer.reduce(state, intent)

        assertNotNull(result)
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun resubscribeIncrementsGeneration() {
        // First subscribe
        var state = connectedState()
        val intent1 = Nip01Intent.Subscribe(testSubId, testFilters)
        var result = reducer.reduce(state, intent1)!!
        state = result.state

        // Re-subscribe with same ID
        val intent2 = Nip01Intent.Subscribe(testSubId, testFilters)
        result = reducer.reduce(state, intent2)!!

        val sub = result.state.subscription(testSubId)
        assertNotNull(sub)
        assertEquals(2, sub.generation)
    }

    @Test
    fun eoseTransitionsSubscriptionToLive() {
        // Setup: create a subscription in CatchingUp state
        var state = connectedState()
        val subscribeResult = reducer.reduce(state, Nip01Intent.Subscribe(testSubId, testFilters))!!
        state = subscribeResult.state

        // Receive EOSE
        val eoseMessage = createEoseMessage(testSubId)
        val result = reducer.reduce(state, InboundMessage(eoseMessage))

        assertNotNull(result)
        val sub = result.state.subscription(testSubId)
        assertNotNull(sub)
        assertEquals(SubscriptionPhase.Live, sub.phase)

        val signal = result.signals.filterIsInstance<Nip01Signal.SubscriptionEose>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testSubId, signal.subscriptionId)
        assertEquals(1, signal.generation)
    }

    @Test
    fun eoseForUnknownSubscriptionIsIgnored() {
        val state = connectedState()
        val eoseMessage = createEoseMessage(testSubId)
        val result = reducer.reduce(state, InboundMessage(eoseMessage))

        assertNotNull(result)
        assertEquals(state, result.state)
    }

    @Test
    fun closeSubscriptionSendsCloseMessage() {
        // Setup: create a live subscription
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Subscribe(testSubId, testFilters))!!.state
        val eoseMessage = createEoseMessage(testSubId)
        state = reducer.reduce(state, InboundMessage(eoseMessage))!!.state

        // Close the subscription
        val result = reducer.reduce(state, Nip01Intent.CloseSubscription(testSubId))

        assertNotNull(result)
        val sub = result.state.subscription(testSubId)
        assertNotNull(sub)
        assertEquals(SubscriptionPhase.Closing, sub.phase)

        assertTrue(result.effects.any { it is ProtocolEffect.Send })
    }

    @Test
    fun closedMessageTransitionsToClosedPhase() {
        // Setup: subscription in Closing state
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Subscribe(testSubId, testFilters))!!.state
        state = reducer.reduce(state, Nip01Intent.CloseSubscription(testSubId))!!.state

        // Receive CLOSED
        val closedMessage = createClosedMessage(testSubId, "subscription closed")
        val result = reducer.reduce(state, InboundMessage(closedMessage))

        assertNotNull(result)
        val sub = result.state.subscription(testSubId)
        assertNotNull(sub)
        assertEquals(SubscriptionPhase.Closed, sub.phase)
        assertEquals("subscription closed", sub.closedReason)

        val signal = result.signals.filterIsInstance<Nip01Signal.SubscriptionClosed>().firstOrNull()
        assertNotNull(signal)
        assertTrue(signal.byRelay)
    }

    @Test
    fun forgetSubscriptionRemovesState() {
        // Setup: subscription exists
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Subscribe(testSubId, testFilters))!!.state

        // Forget
        val result = reducer.reduce(state, Nip01Intent.ForgetSubscription(testSubId))

        assertNotNull(result)
        assertNull(result.state.subscription(testSubId))
    }

    @Test
    fun disconnectInterruptsActiveSubscriptions() {
        // Setup: active subscription
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Subscribe(testSubId, testFilters))!!.state

        // Disconnect
        val result = reducer.reduce(state, TransportInput.Closed(CloseInfo(CloseInfo.NORMAL_CLOSURE, "disconnect", false)))

        assertNotNull(result)
        val sub = result.state.subscription(testSubId)
        assertNotNull(sub)
        assertEquals(SubscriptionPhase.Interrupted, sub.phase)

        val signal = result.signals.filterIsInstance<Nip01Signal.SubscriptionInterrupted>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testSubId, signal.subscriptionId)
    }

    // ===== Event Delivery Tests =====

    @Test
    fun eventIsDeliveredToActiveSubscription() {
        // Setup: active subscription in CatchingUp state
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Subscribe(testSubId, testFilters))!!.state

        // Receive event
        val eventMessage = createRelayEventMessage(testSubId, testEvent)
        val result = reducer.reduce(state, InboundMessage(eventMessage))

        assertNotNull(result)
        val signal = result.signals.filterIsInstance<Nip01Signal.SubscriptionEvent>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testSubId, signal.subscriptionId)
        assertEquals(1, signal.generation)
        assertEquals(testEvent, signal.event)
    }

    @Test
    fun eventForInactiveSubscriptionIsIgnored() {
        // Setup: subscription in Closed state
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Subscribe(testSubId, testFilters))!!.state
        state = state.withSubscription(
            state.subscription(testSubId)!!.copy(phase = SubscriptionPhase.Closed)
        )

        // Receive event
        val eventMessage = createRelayEventMessage(testSubId, testEvent)
        val result = reducer.reduce(state, InboundMessage(eventMessage))

        assertNotNull(result)
        assertTrue(result.signals.filterIsInstance<Nip01Signal.SubscriptionEvent>().isEmpty())
    }

    @Test
    fun eventForUnknownSubscriptionIsIgnored() {
        val state = connectedState()
        val eventMessage = createRelayEventMessage(testSubId, testEvent)
        val result = reducer.reduce(state, InboundMessage(eventMessage))

        assertNotNull(result)
        assertEquals(state, result.state)
    }

    // ===== Publish Tests =====

    @Test
    fun publishWhenConnectedCreatesPublishState() {
        val state = connectedState()
        val intent = Nip01Intent.Publish(testEvent)
        val result = reducer.reduce(state, intent)

        assertNotNull(result)
        val pub = result.state.publish(testEventId)
        assertNotNull(pub)
        assertEquals(testEventId, pub.eventId)
        assertEquals(PublishStatus.Pending, pub.status)

        // Should send EVENT message
        assertTrue(result.effects.any { it is ProtocolEffect.Send && it.message is EventClientMessage })

        // Should emit PublishQueued signal
        val signal = result.signals.filterIsInstance<Nip01Signal.PublishQueued>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testEventId, signal.eventId)
    }

    @Test
    fun publishWhenNotConnectedDoesNothing() {
        val state = ProtocolState() // disconnected
        val intent = Nip01Intent.Publish(testEvent)
        val result = reducer.reduce(state, intent)

        assertNotNull(result)
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun okAcceptedTransitionsToAccepted() {
        // Setup: pending publish
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Publish(testEvent))!!.state

        // Receive OK accepted
        val okMessage = createOkMessage(testEventId, true, "")
        val result = reducer.reduce(state, InboundMessage(okMessage))

        assertNotNull(result)
        val pub = result.state.publish(testEventId)
        assertNotNull(pub)
        assertEquals(PublishStatus.Accepted, pub.status)

        val signal = result.signals.filterIsInstance<Nip01Signal.PublishAccepted>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testEventId, signal.eventId)
    }

    @Test
    fun okRejectedTransitionsToRejected() {
        // Setup: pending publish
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Publish(testEvent))!!.state

        // Receive OK rejected
        val okMessage = createOkMessage(testEventId, false, "error: invalid signature")
        val result = reducer.reduce(state, InboundMessage(okMessage))

        assertNotNull(result)
        val pub = result.state.publish(testEventId)
        assertNotNull(pub)
        assertEquals(PublishStatus.Rejected, pub.status)
        assertEquals("error: invalid signature", pub.okMessage)

        val signal = result.signals.filterIsInstance<Nip01Signal.PublishRejected>().firstOrNull()
        assertNotNull(signal)
    }

    @Test
    fun acceptedStatusIsSticky() {
        // Setup: accepted publish
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Publish(testEvent))!!.state
        state = reducer.reduce(state, InboundMessage(createOkMessage(testEventId, true, "")))!!.state

        // Re-publish same event
        val result = reducer.reduce(state, Nip01Intent.Publish(testEvent))

        assertNotNull(result)
        val pub = result.state.publish(testEventId)
        assertNotNull(pub)
        assertEquals(PublishStatus.Accepted, pub.status) // Still accepted, not Pending
    }

    @Test
    fun lateOkForForgottenPublishIsIgnored() {
        // Setup: create and forget a publish
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Publish(testEvent))!!.state
        state = reducer.reduce(state, Nip01Intent.ForgetPublish(testEventId))!!.state

        // Late OK arrives
        val okMessage = createOkMessage(testEventId, true, "")
        val result = reducer.reduce(state, InboundMessage(okMessage))

        assertNotNull(result)
        assertEquals(state, result.state)
    }

    @Test
    fun forgetPublishRemovesState() {
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Publish(testEvent))!!.state

        val result = reducer.reduce(state, Nip01Intent.ForgetPublish(testEventId))

        assertNotNull(result)
        assertNull(result.state.publish(testEventId))
    }

    @Test
    fun disconnectInterruptsPendingPublishes() {
        // Setup: pending publish
        var state = connectedState()
        state = reducer.reduce(state, Nip01Intent.Publish(testEvent))!!.state

        // Disconnect
        val result = reducer.reduce(state, TransportInput.Closed(CloseInfo(CloseInfo.NORMAL_CLOSURE, "disconnect", false)))

        assertNotNull(result)
        val pub = result.state.publish(testEventId)
        assertNotNull(pub)
        assertEquals(PublishStatus.Interrupted, pub.status)

        val signal = result.signals.filterIsInstance<Nip01Signal.PublishInterrupted>().firstOrNull()
        assertNotNull(signal)
        assertEquals(testEventId, signal.eventId)
    }

    // ===== Notice Tests =====

    @Test
    fun noticeMessageEmitsSignal() {
        val state = connectedState()
        val noticeMessage = createNoticeMessage("This is a notice")
        val result = reducer.reduce(state, InboundMessage(noticeMessage))

        assertNotNull(result)
        val signal = result.signals.filterIsInstance<Nip01Signal.Notice>().firstOrNull()
        assertNotNull(signal)
        assertEquals("This is a notice", signal.message)
    }

    // ===== Unhandled Input Test =====

    @Test
    fun unhandledInputReturnsNull() {
        val state = connectedState()
        // Create a custom input that the reducer doesn't handle
        val customInput = object : io.github.nicolals.nostr.core.protocol.ProtocolInput {}
        val result = reducer.reduce(state, customInput)

        assertNull(result)
    }

    // ===== Test Message Factories =====

    private fun createEoseMessage(subId: SubscriptionId): EoseMessage {
        val array = JsonArray(listOf(JsonString("EOSE"), JsonString(subId.value)))
        return EoseMessage(RawRelayMessage(array), subId.value, subId)
    }

    private fun createClosedMessage(subId: SubscriptionId, reason: String): ClosedMessage {
        val array = JsonArray(listOf(JsonString("CLOSED"), JsonString(subId.value), JsonString(reason)))
        return ClosedMessage(RawRelayMessage(array), subId.value, subId, reason)
    }

    private fun createOkMessage(eventId: EventId, accepted: Boolean, message: String): OkMessage {
        val array = JsonArray(listOf(
            JsonString("OK"),
            JsonString(eventId.hex),
            JsonBoolean(accepted),
            JsonString(message)
        ))
        return OkMessage(RawRelayMessage(array), eventId.hex, eventId, accepted, message)
    }

    private fun createNoticeMessage(message: String): NoticeMessage {
        val array = JsonArray(listOf(JsonString("NOTICE"), JsonString(message)))
        return NoticeMessage(RawRelayMessage(array), message)
    }

    private fun createRelayEventMessage(subId: SubscriptionId, event: Event): RelayEventMessage {
        val wireEvent = WireEvent(
            id = event.id.hex,
            pubkey = event.pubkey.hex,
            createdAt = event.createdAt.value,
            kind = event.kind.value,
            tags = event.tags,
            content = event.content,
            sig = event.sig.hex,
        )
        val array = JsonArray(listOf(JsonString("EVENT"), JsonString(subId.value), JsonArray(emptyList())))
        return RelayEventMessage(RawRelayMessage(array), subId.value, subId, wireEvent, event)
    }
}
