package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.JsonValue
import io.github.nicolals.nostr.core.codec.NostrJsonCodec
import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.message.raw.RawClientMessage
import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.message.view.RelayMessageView
import io.github.nicolals.nostr.core.message.view.UnknownClientMessage
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipRegistry
import io.github.nicolals.nostr.core.protocol.InboundMessage
import io.github.nicolals.nostr.core.protocol.ModuleStateKey
import io.github.nicolals.nostr.core.protocol.ProtocolEffect
import io.github.nicolals.nostr.core.protocol.AdmissionDecision
import io.github.nicolals.nostr.core.protocol.OperationAdmission
import io.github.nicolals.nostr.core.protocol.OperationIntent
import io.github.nicolals.nostr.core.protocol.ProtocolIntent
import io.github.nicolals.nostr.core.protocol.ProtocolInput
import io.github.nicolals.nostr.core.protocol.ProtocolReducer
import io.github.nicolals.nostr.core.protocol.ProtocolSignal
import io.github.nicolals.nostr.core.protocol.ProtocolState
import io.github.nicolals.nostr.core.protocol.ReduceResult
import io.github.nicolals.nostr.core.runtime.SessionConfig
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportResult
import io.github.nicolals.nostr.nip01.protocol.Nip01Signal
import io.github.nicolals.nostr.nip01.protocol.Nip01Intent
import io.github.nicolals.nostr.nip01.Nip01Module
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExtensionTest {
    private data object CustomRequest : OperationIntent
    private data object CustomReplyReceived : ProtocolSignal
    private data class CustomReply(override val raw: RawRelayMessage) : RelayMessageView
    private data object OpenGate : ProtocolIntent
    private object GateOpen : ModuleStateKey<Boolean> {
        override val id = "test.gate.open"
        override val default = false
    }

    private val gateModule = object : NipModule {
        override val id = "test.gate"
        override val admissionPolicies = listOf(OperationAdmission { state, _ ->
            if (state.moduleStates[GateOpen]) AdmissionDecision.Allow else AdmissionDecision.Defer("Gate closed")
        })
        override val protocolReducers = listOf(object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? = when {
                input == OpenGate -> ReduceResult(
                    state.withModuleStates(state.moduleStates.set(GateOpen, true)),
                )
                else -> null
            }
        })
    }

    private val request = UnknownClientMessage(RawClientMessage(JsonArray(listOf(JsonString("CUSTOM")))))
    private val customModule = object : NipModule {
        override val id = "test.SessionExtensionTest.2"
        override val relayMessageUpgraders = listOf(object : RelayMessageUpgrader<CustomReply> {
            override val tokens = setOf("CUSTOM_REPLY")
            override fun upgradeOrNull(raw: RawRelayMessage, token: String) = CustomReply(raw)
        })
        override val protocolReducers = listOf(object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? = when {
                input == CustomRequest -> ReduceResult.withEffects(state, ProtocolEffect.Send(request))
                input is InboundMessage && input.message is CustomReply ->
                    ReduceResult.withSignals(state, CustomReplyReceived)
                else -> null
            }
        })
    }

    @Test fun additionalModuleRejectsManagedOperationsBeforeSending() = runTest {
        val denial = object : NipModule {
            override val id = "test.denial"
            override val admissionPolicies = listOf(OperationAdmission { _, _ -> AdmissionDecision.Reject("Denied") })
        }
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), TestWireCodec(), AcceptTestEvents, factory, this,
            additionalModules = listOf(denial),
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val publication = session.publish(TestFixtures.testEvent)
        val subscription = session.subscribe(TestFixtures.testFilters)
        runCurrent()
        assertEquals(PublishStatus.Rejected("Denied"), publication.status)
        assertEquals(SubscriptionStatus.Closed("Denied"), subscription.status)
        assertTrue(factory.lastConnection!!.sentMessages.isEmpty())
        assertEquals(AdmissionDecision.Reject("Denied"), session.submit(CustomRequest))
        session.closeAndJoin()
    }

    @Test fun closedDeferredSubscriptionCannotBeResumed() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), TestWireCodec(), AcceptTestEvents, factory, this,
            additionalModules = listOf(gateModule),
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val subscription = session.subscribe(TestFixtures.testFilters)
        runCurrent()
        assertIs<SubscriptionStatus.Deferred>(subscription.status)
        subscription.close()
        runCurrent()
        session.submit(OpenGate)
        assertIs<SubscriptionStatus.Closed>(subscription.status)
        assertTrue(factory.lastConnection!!.sentMessages.isEmpty())
        assertEquals(AdmissionDecision.Reject("Unsupported intent"), session.submit(CustomRequest))
        session.closeAndJoin()
    }

    @Test fun admissionInstalledAfterBaseAutomaticallyResumesManagedHandles() = runTest {
        val codec = TestWireCodec()
        val registry = NipRegistry.Builder(codec).install(Nip01Module(codec)).install(gateModule).build()
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            url = RelayUrl.parse("wss://test.relay"), registry = registry, jsonCodec = codec,
            eventVerifier = AcceptTestEvents, transportFactory = factory, scope = this,
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val subscribe = Nip01Intent.Subscribe(TestFixtures.testSubId, TestFixtures.testFilters)
        val publish = Nip01Intent.Publish(TestFixtures.testEvent)
        val subscription = session.subscribe(subscribe.filters, subscribe.subscriptionId)
        val publication = session.publish(publish.event)
        runCurrent()
        assertTrue(factory.lastConnection!!.sentMessages.isEmpty())
        assertEquals(SubscriptionStatus.Deferred("Gate closed"), subscription.status)
        assertEquals(PublishStatus.Deferred("Gate closed"), publication.status)

        session.submit(OpenGate)
        assertEquals(2, factory.lastConnection!!.sentMessages.size)
        assertEquals(SubscriptionStatus.CatchingUp, subscription.status)
        assertEquals(PublishStatus.Pending, publication.status)
        factory.lastConnection!!.simulateMessage(TestFixtures.eoseJson(subscription.subscriptionId))
        factory.lastConnection!!.simulateMessage(TestFixtures.okJson(publication.eventId, true))
        runCurrent()
        assertEquals(SubscriptionStatus.Live, subscription.status)
        assertIs<PublishStatus.Accepted>(publication.status)
        session.closeAndJoin()
    }

    @Test fun poolKeepsAdmissionStateIndependentAcrossSessions() = runTest {
        val codec = TestWireCodec()
        val registry = NipRegistry.Builder(codec).install(Nip01Module(codec)).install(gateModule).build()
        val factory = FakeRelayTransportFactory()
        val pool = CoroutinesRelayPool(
            registry = registry, jsonCodec = codec, eventVerifier = AcceptTestEvents,
            transportFactory = factory, scope = this,
        )
        val firstUrl = RelayUrl.parse("wss://first.relay")
        val secondUrl = RelayUrl.parse("wss://second.relay")
        val first = pool.addRelay(firstUrl)
        val second = pool.addRelay(secondUrl)
        pool.connectAll()
        runCurrent()
        factory.connections.forEach { it.simulateOpen() }
        runCurrent()
        val subscription = pool.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        runCurrent()
        assertTrue(factory.connections.all { it.sentMessages.isEmpty() })
        first.submit(OpenGate)
        assertEquals(1, factory.connections.single { it.url == firstUrl }.sentMessages.size)
        assertTrue(factory.connections.single { it.url == secondUrl }.sentMessages.isEmpty())
        assertEquals(SubscriptionStatus.CatchingUp, subscription.handles.getValue(firstUrl).status)
        assertEquals(SubscriptionStatus.Deferred("Gate closed"), subscription.handles.getValue(secondUrl).status)
        pool.close()
        subscription.close()
    }

    @Test fun thirdPartyModuleCanSubmitObserveAndSendWithoutRuntimeChanges() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), TestWireCodec(), AcceptTestEvents, factory, this,
            additionalModules = listOf(customModule),
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val replies = mutableListOf<CustomReply>()
        val signals = mutableListOf<CustomReplyReceived>()
        launch { session.messages.filterIsInstance<CustomReply>().toList(replies) }
        launch { session.signals.filterIsInstance<CustomReplyReceived>().toList(signals) }
        runCurrent()
        session.submit(CustomRequest)
        assertEquals(listOf("[\"CUSTOM\"]"), factory.lastConnection!!.sentMessages)
        factory.lastConnection!!.simulateMessage("[\"CUSTOM_REPLY\"]")
        runCurrent()
        assertEquals(1, replies.size)
        assertEquals(listOf(CustomReplyReceived), signals)
        assertIs<TransportResult.Success<Unit>>(session.send(request))
        assertEquals(2, factory.lastConnection!!.sentMessages.size)
        session.closeAndJoin()
    }

    @Test fun rejectedEventCannotReachObserversOrPoisonDeduplication() = runTest {
        var checks = 0
        val verifier = EventVerifier {
            checks++
            if (checks == 1) CryptoResult.Err(CryptoError.VerificationFailed("Invalid signature"))
            else CryptoResult.Ok(Unit)
        }
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), TestWireCodec(), verifier, factory, this,
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val subscription = session.subscribe(TestFixtures.testFilters, TestFixtures.testSubId)
        var events = 0
        var inboundEvents = 0
        var eventSignals = 0
        var rejections = 0
        launch { subscription.events.collect { events++ } }
        launch { session.messages.collect { if (it.token == "EVENT") inboundEvents++ } }
        launch { session.signals.collect { if (it is Nip01Signal.SubscriptionEvent) eventSignals++ } }
        val errors = launch { session.errors.collect { if (it is SessionError.EventRejected) rejections++ } }
        runCurrent()
        val text = TestFixtures.eventJson(subscription.subscriptionId, TestFixtures.testEvent)
        factory.lastConnection!!.simulateMessage(text)
        runCurrent()
        assertEquals(0, events)
        assertEquals(0, inboundEvents)
        assertEquals(0, eventSignals)
        assertEquals(1, rejections)
        factory.lastConnection!!.simulateMessage(text)
        runCurrent()
        assertEquals(1, events)
        assertEquals(1, inboundEvents)
        assertEquals(1, eventSignals)
        errors.cancel()
        session.closeAndJoin()
    }

    @Test fun overflowingObserverFailsExplicitlyWithoutBlockingOthersOrClose() = runTest {
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), TestWireCodec(), AcceptTestEvents, factory, this,
            config = SessionConfig(observerBufferCapacity = 1), additionalModules = listOf(customModule),
        )
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        val overflow = CompletableDeferred<Throwable>()
        var fastCount = 0
        launch {
            session.signals.filterIsInstance<CustomReplyReceived>()
                .catch { overflow.complete(it) }
                .collect { delay(1000) }
        }
        launch { session.signals.filterIsInstance<CustomReplyReceived>().collect { fastCount++ } }
        runCurrent()
        repeat(3) {
            factory.lastConnection!!.simulateMessage("[\"CUSTOM_REPLY\"]")
            runCurrent()
        }
        assertEquals(3, fastCount)
        session.closeAndJoin()
        assertTrue(session.isClosed)
        advanceTimeBy(2000)
        runCurrent()
        assertIs<SessionObservationOverflowException>(overflow.await())
    }

    @Test fun diagnosticsDoNotRetainParserOrReducerPayloads() = runTest {
        val privateContent = "private-payment-payload"
        val codec = object : NostrJsonCodec by TestWireCodec() {
            override fun parse(json: String): JsonValue = error("Parser rejected $privateContent: $json")
        }
        val module = object : NipModule {
        override val id = "test.SessionExtensionTest.3"
            override val protocolReducers = listOf(object : ProtocolReducer {
                override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                    if (input == CustomRequest) error("Reducer rejected $privateContent")
                    return null
                }
            })
        }
        val factory = FakeRelayTransportFactory()
        val session = CoroutinesRelaySession(
            RelayUrl.parse("wss://test.relay"), codec, AcceptTestEvents, factory, this,
            additionalModules = listOf(module),
        )
        val diagnostics = mutableListOf<SessionError>()
        val collector = launch { session.errors.toList(diagnostics) }
        session.connect()
        runCurrent()
        factory.lastConnection!!.simulateOpen()
        runCurrent()
        factory.lastConnection!!.simulateMessage(privateContent)
        runCurrent()
        val exception = assertFailsWith<IllegalStateException> { session.submit(CustomRequest) }
        runCurrent()
        assertEquals(2, diagnostics.size)
        assertIs<SessionError.ParseError>(diagnostics[0])
        assertIs<SessionError.ProcessingError>(diagnostics[1])
        assertFalse(diagnostics.toString().contains(privateContent))
        assertFalse(exception.toString().contains(privateContent))
        collector.cancel()
        session.closeAndJoin()
    }
}
