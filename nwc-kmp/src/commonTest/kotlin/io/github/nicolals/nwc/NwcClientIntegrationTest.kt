package io.github.nicolals.nwc

import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.codec.JsonArray
import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.codec.JsonObject
import io.github.nicolals.nostr.core.crypto.EventTemplateSigner
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.codec.wire.toEventOrNull
import io.github.nicolals.nostr.core.codec.wire.toWireEvent
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.RelayConnection
import io.github.nicolals.nostr.core.transport.RelayConnectionListener
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportConfig
import io.github.nicolals.nostr.core.transport.TransportResult
import io.github.nicolals.nostr.core.transport.TransportState
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip04.LocalNip04Cipher
import io.github.nicolals.nostr.nip44.LocalNip44Cipher
import io.github.nicolals.nostr.nip47.Nip47Cipher
import io.github.nicolals.nostr.nip47.NwcCipher
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.event.NwcRequestEvent
import io.github.nicolals.nostr.nip47.event.NwcNotificationEvent
import io.github.nicolals.nostr.nip47.event.NwcInfoEvent
import io.github.nicolals.nostr.nip47.event.NwcResponseEvent
import io.github.nicolals.nostr.nip47.model.NwcBalanceResult
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcPayResult
import io.github.nicolals.nostr.nip47.model.NwcPaymentReceivedNotification
import io.github.nicolals.nostr.nip47.model.NwcTransaction
import io.github.nicolals.nostr.nip47.model.NwcUnknownRequest
import io.github.nicolals.nostr.nip47.model.NwcUnknownResult
import io.github.nicolals.nostr.nip47.model.NwcResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NwcClientIntegrationTest {
    @Test
    fun overflowingResponseStreamFailsClientAndPendingRequest() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo)
        try {
            runCurrent()
            assertTrue(client.awaitReady())
            val relay = factory.connections.single()
            val request = async { client.getBalance() }
            runCurrent()
            val publication = relay.publications.last()
            relay.ack(publication)
            runCurrent()
            val unrelated = response(publication.copy(
                id = io.github.nicolals.nostr.core.primitives.EventId.fromHex("f".repeat(64)),
            ), 100)
            repeat(300) { relay.deliver(unrelated) }
            runCurrent()
            assertTrue(client.state.value is NwcClientState.Failed)
            assertTrue(request.await() is NwcResult.Failure)
            assertEquals(0, client.pendingRequestCount)
            assertEquals(TransportState.Closed, relay.state)
        } finally {
            client.close()
        }
    }
    private val codec = KotlinxNostrJsonCodec()
    private val walletSecret = SecretKey.fromHex("1".padStart(64, '0'))
    private val clientSecret = SecretKey.fromHex("2".padStart(64, '0'))
    private val attackerSecret = SecretKey.fromHex("3".padStart(64, '0'))
    private val walletSigner = NostrSigner.fromSecretKey(walletSecret).getOrThrow()
    private val clientSigner = NostrSigner.fromSecretKey(clientSecret).getOrThrow()
    private val attackerSigner = NostrSigner.fromSecretKey(attackerSecret).getOrThrow()
    private val walletCipher = cipher(walletSecret)
    private val uri = NwcConnectionUri.parse(
        "nostr+walletconnect://${walletSigner.pubkey.hex}?relay=wss://test.example&secret=${clientSecret.hex}"
    )!!
    private val walletInfo = WalletInfo(
        capabilities = setOf(NwcCapability.GET_BALANCE, NwcCapability.MULTI_PAY_INVOICE),
        notifications = emptySet(),
        encryptions = setOf(NwcEncryption.NIP44_V2),
        preferredEncryption = NwcEncryption.NIP44_V2,
    )

    @Test
    fun forgedWalletAndModifiedReplayCannotCompleteARequest() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo)
        try {
            runCurrent()
            assertTrue(client.awaitReady())
            val relay = factory.connections.single()
            val first = async { client.getBalance() }
            runCurrent()
            val firstRequest = relay.publications.last()
            val genuine = response(firstRequest, 100)
            relay.deliver(genuine)
            relay.ack(firstRequest)
            runCurrent()
            assertEquals(100L, first.await().getOrThrow().msats)

            val next = async { client.getBalance() }
            runCurrent()
            val nextRequest = relay.publications.last()
            relay.ack(nextRequest)
            val attackerResponse = attackerSigner.sign(NwcResponseEvent.template(
                clientPubkey = clientSigner.pubkey,
                response = NwcResponse("get_balance", result = NwcBalanceResult(999)),
                createdAt = UnixTimeSeconds.now(),
                codec = codec,
                cipher = cipher(attackerSecret),
                requestEventId = nextRequest.id,
            ).getOrThrow()).getOrThrow().event
            relay.deliver(attackerResponse)
            relay.deliver(genuine.copy(tags = genuine.tags.map {
                if (it.firstOrNull() == "e") listOf("e", nextRequest.id.hex) else it
            }))
            runCurrent()
            assertFalse(next.isCompleted)

            // A wallet-signed event addressed to someone else must also be rejected,
            // even when its ciphertext can be decrypted by this client.
            val correct = response(nextRequest, 200)
            val wrongRecipient = walletSigner.sign(EventDraft(
                correct.createdAt, correct.kind,
                correct.tags.map { if (it.firstOrNull() == "p") listOf("p", attackerSigner.pubkey.hex) else it },
                correct.content,
            )).getOrThrow()
            relay.deliver(wrongRecipient)
            runCurrent()
            assertFalse(next.isCompleted)
            relay.deliver(response(nextRequest, 999, encryption = NwcEncryption.NIP04))
            runCurrent()
            assertFalse(next.isCompleted)
            for (tags in listOf(
                correct.tags.map { if (it.firstOrNull() == "encryption") listOf("encryption", "unknown") else it },
                correct.tags + listOf(listOf("encryption", "nip44_v2")),
            )) {
                relay.deliver(walletSigner.sign(EventDraft(correct.createdAt, correct.kind, tags, correct.content)).getOrThrow())
                runCurrent()
                assertFalse(next.isCompleted)
            }
            relay.deliver(correct)
            runCurrent()
            assertEquals(200L, next.await().getOrThrow().msats)
        } finally {
            client.close()
        }
    }

    @Test
    fun batchRetainsResponsesBeforeAckAndDuringAwait() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo)
        try {
            runCurrent()
            val relay = factory.connections.single()
            val result = async {
                client.multiPayInvoice(listOf(MultiPayInvoiceItem("a", "invoice-a"), MultiPayInvoiceItem("b", "invoice-b")))
            }
            runCurrent()
            val request = relay.publications.last()
            relay.deliver(paymentResponse(request, "b"))
            runCurrent()
            relay.ack(request)
            runCurrent()
            assertFalse(result.isCompleted)
            relay.deliver(paymentResponse(request, "a"))
            runCurrent()
            val items = result.await().getOrThrow()
            assertEquals(setOf("a", "b"), items.keys)
            assertTrue(items.values.all { it is MultiPayItemResult.Success })
        } finally {
            client.close()
        }
    }

    @Test
    fun closeDuringInfoFetchCannotResurrectTheClient() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory)
        runCurrent()
        assertEquals(NwcClientState.Connecting, client.state.value)
        assertTrue(factory.connections.single().subscriptions.values.any { NwcInfoEvent.KIND.value in it })
        client.close()
        advanceTimeBy(6_000)
        runCurrent()
        client.connect(forceReconnect = true)
        runCurrent()
        assertEquals(NwcClientState.Closed, client.state.value)
        assertTrue(factory.connections.all { it.state == TransportState.Closed })
        assertEquals(1, factory.connections.size)
    }

    @Test
    fun onlyAuthenticWalletInfoCanChooseEncryption() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory)
        try {
            runCurrent()
            val relay = factory.connections.single()
            val template = NwcInfoEvent.template(
                capabilities = listOf("get_balance"),
                createdAt = UnixTimeSeconds.now(),
                encryption = listOf(NwcEncryption.NIP44_V2),
            )
            relay.deliver(attackerSigner.sign(template).getOrThrow().event)
            runCurrent()
            assertEquals(NwcClientState.Connecting, client.state.value)
            relay.deliver(walletSigner.sign(template).getOrThrow().event)
            runCurrent()
            assertEquals(NwcClientState.Ready, client.state.value)
            assertEquals(NwcEncryption.NIP44_V2, client.encryption)
        } finally {
            client.close()
        }
    }

    @Test
    fun notificationsAuthenticateTheWalletAndStartAfterColdDiscovery() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory)
        val received = mutableListOf<WalletNotification>()
        backgroundScope.launch { client.notifications.collect { received += it } }
        try {
            runCurrent()
            val relay = factory.connections.single()
            relay.deliver(walletSigner.sign(NwcInfoEvent.template(
                capabilities = listOf("get_balance", "notifications"),
                createdAt = UnixTimeSeconds.now(),
                encryption = listOf(NwcEncryption.NIP44_V2),
                notifications = listOf("payment_received"),
            )).getOrThrow().event)
            runCurrent()
            assertTrue(client.awaitReady())
            val notification = NwcPaymentReceivedNotification(NwcTransaction(amountMsat = 123, paymentHash = "hash"))
            val forged = attackerSigner.sign(NwcNotificationEvent.template(
                clientPubkey = clientSigner.pubkey, notification = notification,
                createdAt = UnixTimeSeconds.now(), codec = codec, cipher = cipher(attackerSecret),
            ).getOrThrow()).getOrThrow().event
            relay.deliver(forged)
            runCurrent()
            assertTrue(received.isEmpty())
            val authentic = walletSigner.sign(NwcNotificationEvent.template(
                clientPubkey = clientSigner.pubkey, notification = notification,
                createdAt = UnixTimeSeconds.now(), codec = codec, cipher = walletCipher,
            ).getOrThrow()).getOrThrow().event
            relay.deliver(authentic)
            runCurrent()
            assertEquals(1, received.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun forcedReconnectCancelsThePreviousInitialization() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory)
        try {
            runCurrent()
            client.connect(forceReconnect = true)
            runCurrent()
            assertEquals(2, factory.connections.size)
            assertEquals(TransportState.Closed, factory.connections.first().state)
            factory.connections.last().deliver(walletSigner.sign(NwcInfoEvent.template(
                capabilities = listOf("get_balance"), createdAt = UnixTimeSeconds.now(),
                encryption = listOf(NwcEncryption.NIP44_V2),
            )).getOrThrow().event)
            runCurrent()
            assertEquals(NwcClientState.Ready, client.state.value)
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(NwcClientState.Ready, client.state.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellingBeforePublishAckRemovesThePendingRequest() = runTest {
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo)
        try {
            runCurrent()
            var returnedToCancelledCaller = false
            val result = async { client.getBalance().also { returnedToCancelledCaller = true } }
            runCurrent()
            assertEquals(1, client.pendingRequestCount)
            result.cancelAndJoin()
            assertTrue(result.isCancelled)
            assertFalse(returnedToCancelledCaller)
            assertEquals(0, client.pendingRequestCount)
            assertEquals(NwcClientState.Ready, client.state.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun extensionsUseInjectedAsynchronousSigningAndEncryption() = runTest {
        var signed = false
        var encrypted = false
        val signing = object : EventTemplateSigner {
            override val pubkey = clientSigner.pubkey
            override suspend fun sign(draft: EventDraft) = clientSigner.sign(draft).also {
                delay(10)
                signed = true
            }
        }
        val localCipher = cipher(clientSecret)
        val encryption = object : NwcCipher {
            override suspend fun encrypt(plaintext: String, encryption: NwcEncryption, recipientPublicKey: PublicKey) =
                localCipher.encrypt(plaintext, encryption, recipientPublicKey).also {
                    delay(10)
                    encrypted = true
                }
            override suspend fun decrypt(content: String, encryption: NwcEncryption, senderPublicKey: PublicKey) =
                localCipher.decrypt(content, encryption, senderPublicKey)
        }
        val factory = TestRelayFactory(codec)
        val client = NwcClient(
            NwcConnection(walletSigner.pubkey, signing.pubkey, listOf("wss://test.example")),
            backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo,
            signer = signing, cipher = encryption,
        )
        assertEquals(null, client.uri)
        try {
            runCurrent()
            val extensionRequest = NwcUnknownRequest("wallet_extension", JsonObject(mapOf("value" to JsonString("test"))))
            val result = async { client.request(extensionRequest) }
            advanceTimeBy(30)
            runCurrent()
            assertTrue(signed)
            assertTrue(encrypted)
            val relay = factory.connections.single()
            val requestEvent = relay.publications.last()
            val decoded = NwcRequestEvent.fromEventOrNull(requestEvent, NwcEncryptionSelector { NwcEncryption.NIP44_V2 })!!
                .decryptForWallet(codec, walletCipher).getOrThrow()
            assertEquals(extensionRequest, decoded)
            val value = NwcUnknownResult("wallet_extension", JsonObject(mapOf("ok" to JsonString("yes"))))
            relay.deliver(walletSigner.sign(NwcResponseEvent.template(
                clientPubkey = clientSigner.pubkey,
                response = NwcResponse("wallet_extension", result = value),
                createdAt = UnixTimeSeconds.now(), codec = codec, cipher = walletCipher, requestEventId = requestEvent.id,
            ).getOrThrow()).getOrThrow().event)
            relay.ack(requestEvent)
            runCurrent()
            assertEquals(value, result.await().getOrThrow().result)
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellingParentClosesConnectionsButLeavesInjectedFactoryOwnedByCaller() = runTest {
        val factory = TestRelayFactory(codec)
        val parent = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val client = NwcClient(uri, parent, transportFactory = factory, cachedWalletInfo = walletInfo)
        runCurrent()
        assertEquals(NwcClientState.Ready, client.state.value)
        parent.cancel()
        runCurrent()
        assertEquals(NwcClientState.Closed, client.state.value)
        assertTrue(factory.connections.all { it.state == TransportState.Closed })
        assertFalse(factory.closed)
        client.close()
    }

    @Test
    fun closeAndReconnectReturnNotSentWhileCancellingRemotePreparation() = runTest {
        for (reconnect in listOf(false, true)) for (batch in listOf(false, true)) for (encrypting in listOf(false, true)) {
            val approvalStarted = CompletableDeferred<Unit>()
            var approvalCancelled = false
            suspend fun approve() {
                approvalStarted.complete(Unit)
                try {
                    delay(60_000)
                } finally {
                    approvalCancelled = true
                }
            }
            val remoteSigner = object : EventTemplateSigner {
                override val pubkey = clientSigner.pubkey
                override suspend fun sign(draft: EventDraft): io.github.nicolals.nostr.core.crypto.CryptoResult<Event> {
                    if (!encrypting) approve()
                    return clientSigner.sign(draft)
                }
            }
            val localCipher = cipher(clientSecret)
            val remoteCipher = object : NwcCipher {
                override suspend fun encrypt(plaintext: String, encryption: NwcEncryption, recipientPublicKey: PublicKey): io.github.nicolals.nostr.core.crypto.CryptoResult<String> {
                    if (encrypting) approve()
                    return localCipher.encrypt(plaintext, encryption, recipientPublicKey)
                }
                override suspend fun decrypt(content: String, encryption: NwcEncryption, senderPublicKey: PublicKey) =
                    localCipher.decrypt(content, encryption, senderPublicKey)
            }
            val factory = TestRelayFactory(codec)
            val client = NwcClient(
                uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo,
                signer = remoteSigner, cipher = remoteCipher,
            )
            try {
                runCurrent()
                val result = async {
                    if (batch) client.multiPayInvoice(listOf(MultiPayInvoiceItem("a", "invoice-a"))).map { }
                    else client.getBalance().map { }
                }
                runCurrent()
                assertTrue(approvalStarted.isCompleted)
                if (reconnect) client.connect(forceReconnect = true) else client.close()
                runCurrent()
                assertTrue(approvalCancelled)
                assertTrue(result.await().errorOrNull() is NwcError.ConnectionError)
                assertFalse(result.isCancelled)
                assertTrue(factory.connections.all { it.publications.isEmpty() })
                assertEquals(0, client.pendingRequestCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun closeAndReconnectAfterPublicationReturnAnUncertainOutcomeWithOrWithoutAck() = runTest {
        for (reconnect in listOf(false, true)) for (batch in listOf(false, true)) for (acknowledged in listOf(false, true)) {
            val factory = TestRelayFactory(codec)
            val client = NwcClient(uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo)
            try {
                runCurrent()
                val relay = factory.connections.single()
                val result = async {
                    if (batch) client.multiPayInvoice(listOf(MultiPayInvoiceItem("a", "invoice-a"))).map { }
                    else client.getBalance().map { }
                }
                runCurrent()
                val publication = relay.publications.single()
                if (acknowledged) {
                    relay.ack(publication)
                    runCurrent()
                }
                assertFalse(result.isCompleted)
                if (reconnect) client.connect(forceReconnect = true) else client.close()
                runCurrent()
                assertTrue(result.await().isTimeout)
                assertFalse(result.isCancelled)
                assertEquals(0, client.pendingRequestCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun requestDeadlineIncludesRemoteEncryptionAndPublishAcknowledgment() = runTest {
        val local = cipher(clientSecret)
        var encryptions = 0
        val slowCipher = object : NwcCipher {
            override suspend fun encrypt(plaintext: String, encryption: NwcEncryption, recipientPublicKey: PublicKey): io.github.nicolals.nostr.core.crypto.CryptoResult<String> {
                encryptions++
                if (encryptions == 1) delay(1_000)
                return local.encrypt(plaintext, encryption, recipientPublicKey)
            }
            override suspend fun decrypt(content: String, encryption: NwcEncryption, senderPublicKey: PublicKey) =
                local.decrypt(content, encryption, senderPublicKey)
        }
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo, cipher = slowCipher)
        try {
            runCurrent()
            assertTrue(client.getBalance(timeoutMs = 0).isFailure)
            assertTrue(factory.connections.single().publications.isEmpty())
            val encryptionTimeout = async { client.getBalance(timeoutMs = 100) }
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(encryptionTimeout.await().isTimeout)
            assertTrue(factory.connections.single().publications.isEmpty())
            val acknowledgmentTimeout = async { client.getBalance(timeoutMs = 100) }
            runCurrent()
            assertEquals(1, factory.connections.single().publications.size)
            advanceTimeBy(101)
            runCurrent()
            assertTrue(acknowledgmentTimeout.await().isTimeout)
            assertEquals(0, client.pendingRequestCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun discoveryUsesOneDeadlineAcrossConnectionAndWalletRequest() = runTest {
        val factory = TestRelayFactory(codec)
        val discovery = async { NwcClient.discover(uri.raw, timeoutMs = 1_000, transportFactory = factory) }
        runCurrent()
        advanceTimeBy(700)
        val relay = factory.connections.single()
        relay.deliver(walletSigner.sign(NwcInfoEvent.template(
            capabilities = listOf("get_info"), createdAt = UnixTimeSeconds.now(),
            encryption = listOf(NwcEncryption.NIP44_V2),
        )).getOrThrow().event)
        runCurrent()
        assertEquals(1, relay.publications.size)
        relay.ack(relay.publications.single())
        advanceTimeBy(301)
        runCurrent()
        assertTrue(discovery.await().isTimeout)
        assertEquals(TransportState.Closed, relay.state)
    }

    @Test
    fun injectedProviderExceptionsDoNotExposePlaintextDiagnostics() = runTest {
        val brokenSigner = object : EventTemplateSigner {
            override val pubkey = clientSigner.pubkey
            override suspend fun sign(draft: EventDraft): io.github.nicolals.nostr.core.crypto.CryptoResult<Event> {
                error("sensitive-provider-plaintext")
            }
        }
        val factory = TestRelayFactory(codec)
        val client = NwcClient(uri, backgroundScope, transportFactory = factory, cachedWalletInfo = walletInfo, signer = brokenSigner)
        try {
            runCurrent()
            val result = client.getBalance()
            assertTrue(result.isFailure)
            assertFalse(result.toString().contains("sensitive-provider-plaintext"))
            assertEquals(0, client.pendingRequestCount)
            assertTrue(factory.connections.single().publications.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidSecretScalarsAreRejectedByUriParsing() {
        for (secret in listOf("0".repeat(64), "f".repeat(64))) {
            assertEquals(null, NwcConnectionUri.parse(
                "nostr+walletconnect://${walletSigner.pubkey.hex}?relay=wss://test.example&secret=$secret"
            ))
        }
    }

    @Test
    fun connectionAndDiscoveryDescriptionsDoNotExposeCredentials() {
        assertFalse(uri.toString().contains(clientSecret.hex))
        assertFalse(NwcDeepLink.Callback(uri.raw).toString().contains(clientSecret.hex))
        assertFalse(WalletDiscovery(uri, walletInfo, null).toString().contains(clientSecret.hex))
    }

    private fun cipher(secret: SecretKey) = Nip47Cipher(LocalNip04Cipher(secret, NostrCrypto), LocalNip44Cipher(secret, NostrCrypto))

    private suspend fun response(request: Event, balance: Long, encryption: NwcEncryption = NwcEncryption.NIP44_V2): Event =
        walletSigner.sign(NwcResponseEvent.template(
            clientPubkey = clientSigner.pubkey,
            response = NwcResponse("get_balance", result = NwcBalanceResult(balance)),
            createdAt = UnixTimeSeconds.now(),
            codec = codec,
            cipher = walletCipher,
            requestEventId = request.id,
            encryption = encryption,
        ).getOrThrow()).getOrThrow().event

    private suspend fun paymentResponse(request: Event, id: String): Event =
        walletSigner.sign(NwcResponseEvent.template(
            clientPubkey = clientSigner.pubkey,
            response = NwcResponse("multi_pay_invoice", result = NwcPayResult("preimage-$id")),
            createdAt = UnixTimeSeconds.now(),
            codec = codec,
            cipher = walletCipher,
            requestEventId = request.id,
            responseId = id,
        ).getOrThrow()).getOrThrow().event
}

private class TestRelayFactory(private val codec: KotlinxNostrJsonCodec) : RelayTransportFactory, AutoCloseable {
    var closed = false
    override fun close() { closed = true }
    val connections = mutableListOf<TestRelay>()
    override fun create(url: RelayUrl, config: TransportConfig): RelayConnection =
        TestRelay(url, codec).also(connections::add)
}

/** Deliberately ignores author/recipient filters: the client must distrust the relay. */
private class TestRelay(override val url: RelayUrl, private val codec: KotlinxNostrJsonCodec) : RelayConnection {
    override var state = TransportState.Idle
    private var listener: RelayConnectionListener? = null
    val subscriptions = mutableMapOf<String, Set<Int>>()
    val publications = mutableListOf<Event>()

    override fun setListener(listener: RelayConnectionListener?) { this.listener = listener }
    override fun connect() {
        state = TransportState.Open
        listener?.onOpen()
    }
    override fun send(text: String): TransportResult<Unit> {
        val message = (codec.parse(text) as JsonArray).values
        when ((message.first() as JsonString).value) {
            "REQ" -> {
                val id = (message[1] as JsonString).value
                subscriptions[id] = message.drop(2).flatMap { codec.decodeFilter(it).kinds.orEmpty().map(Long::toInt) }.toSet()
            }
            "EVENT" -> publications += codec.decodeEvent(message[1]).toEventOrNull()!!
            "CLOSE" -> subscriptions.remove((message[1] as JsonString).value)
        }
        return TransportResult.success()
    }
    override fun ping(payload: ByteString?): TransportResult<Unit> {
        listener?.onPong(payload)
        return TransportResult.success()
    }
    override fun close(code: Int, reason: String?) {
        state = TransportState.Closed
        listener?.onClosed(CloseInfo(code, reason, false))
    }
    fun deliver(event: Event) {
        subscriptions.filterValues { event.kind.value in it }.keys.toList().forEach { id ->
            listener?.onMessage(codec.stringify(JsonArray(listOf(
                JsonString("EVENT"), JsonString(id), codec.encodeEvent(event.toWireEvent()),
            ))))
        }
    }
    fun ack(event: Event) {
        listener?.onMessage("[\"OK\",\"${event.id.hex}\",true,\"\"]")
    }
}
