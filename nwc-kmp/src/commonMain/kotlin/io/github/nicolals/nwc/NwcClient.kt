package io.github.nicolals.nwc

import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.codec.NostrJsonCodec
import io.github.nicolals.nostr.core.crypto.EventTemplateSigner
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.runtime.ConnectionStatus
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.runtime.SessionConfig
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrEventVerifier
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip04.Nip04Module
import io.github.nicolals.nostr.nip04.LocalNip04Cipher
import io.github.nicolals.nostr.nip44.LocalNip44Cipher
import io.github.nicolals.nostr.nip47.Nip47Cipher
import io.github.nicolals.nostr.nip47.NwcCipher
import io.github.nicolals.nostr.nip47.Nip47Module
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.event.NwcInfoEvent
import io.github.nicolals.nostr.nip47.event.NwcNotificationEvent
import io.github.nicolals.nostr.nip47.event.NwcRequestEvent
import io.github.nicolals.nostr.nip47.event.NwcResponseEvent
import io.github.nicolals.nostr.nip47.model.NwcBalanceResult
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcError
import io.github.nicolals.nostr.nip47.model.NwcGetBalanceRequest
import io.github.nicolals.nostr.nip47.model.NwcGetInfoRequest
import io.github.nicolals.nostr.nip47.model.NwcGetInfoResult
import io.github.nicolals.nostr.nip47.model.NwcInvoiceRequestItem
import io.github.nicolals.nostr.nip47.model.NwcInvoiceResult
import io.github.nicolals.nostr.nip47.model.NwcKeysendRequestItem
import io.github.nicolals.nostr.nip47.model.NwcListTransactionsRequest
import io.github.nicolals.nostr.nip47.model.NwcListTransactionsResult
import io.github.nicolals.nostr.nip47.model.NwcLookupInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMakeInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMultiPayInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcMultiPayKeysendRequest
import io.github.nicolals.nostr.nip47.model.NwcNotification
import io.github.nicolals.nostr.nip47.model.NwcPayInvoiceRequest
import io.github.nicolals.nostr.nip47.model.NwcPayKeysendRequest
import io.github.nicolals.nostr.nip47.model.NwcPayResult
import io.github.nicolals.nostr.nip47.model.NwcPaymentReceivedNotification
import io.github.nicolals.nostr.nip47.model.NwcPaymentSentNotification
import io.github.nicolals.nostr.nip47.model.NwcRequest
import io.github.nicolals.nostr.nip47.model.NwcResponse
import io.github.nicolals.nostr.nip47.model.NwcResult
import io.github.nicolals.nostr.nip47.model.NwcTlvRecord
import io.github.nicolals.nostr.nip47.model.NwcTransaction
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesPoolSubscriptionHandle
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelayPool
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Nostr Wallet Connect (NWC) client for Kotlin Multiplatform.
 *
 * This client handles all communication with an NWC-enabled lightning wallet,
 * providing a simple, ergonomic API for common operations like:
 * - Checking balance
 * - Paying invoices
 * - Creating invoices
 * - Listing transactions
 *
 * ## Usage
 *
 * ```kotlin
 * // Parse connection URI from QR code or deep link
 * val uri = NwcConnectionUri.parse("nostr+walletconnect://...")
 *     ?: error("Invalid NWC URI")
 *
 * // Create the client
 * val client = NwcClient(uri, scope)
 *
 * // Connect and wait for ready
 * client.connect()
 * if (client.awaitReady(timeoutMs = 5000)) {
 *     // Get balance
 *     when (val result = client.getBalance()) {
 *         is NwcResult.Success -> println("Balance: ${result.value}")
 *         is NwcResult.Failure -> println("Error: ${result.error.message}")
 *     }
 * }
 *
 * // Clean up
 * client.close()
 * ```
 *
 * ## Jetpack Compose Integration
 *
 * The client exposes StateFlow properties that can be collected in Compose:
 *
 * ```kotlin
 * @Composable
 * fun WalletScreen(client: NwcClient) {
 *     val state by client.state.collectAsState()
 *     val notifications by client.notifications.collectAsState(initial = null)
 *
 *     when (state) {
 *         NwcClientState.Connecting -> LoadingIndicator()
 *         NwcClientState.Ready -> WalletContent()
 *         is NwcClientState.Failed -> ErrorView(state.error)
 *         NwcClientState.Closed -> Text("Disconnected")
 *     }
 * }
 * ```
 *
 * ## Lifecycle
 *
 * The client should be created once and reused. For Compose apps, consider:
 * - Creating in a ViewModel with `viewModelScope`
 * - Using Hilt/Koin for DI
 * - Creating a singleton for app-wide access
 *
 * Always call [close] when done to release resources.
 */
class NwcClient private constructor(
    val connection: NwcConnection,
    private val connectionUri: NwcConnectionUri?,
    parentScope: CoroutineScope,
    private val transportFactory: RelayTransportFactory,
    private val codec: NostrJsonCodec,
    private val signer: EventTemplateSigner,
    private val verifier: EventVerifier,
    private val cipher: NwcCipher,
    private val ownedTransport: AutoCloseable?,
    cachedWalletInfo: WalletInfo?,
) {
    // ========== Internal State ==========

    private val clientJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + clientJob)
    private val lifecycleMutex = Mutex()
    private var connectionJob: Job? = null
    private var connectionGeneration = 0L
    private val preparingRequests = mutableSetOf<Job>()

    private val encryptionSelector = NwcEncryptionSelector { _walletInfo.value.preferredEncryption }

    private val additionalModules: List<NipModule> = listOf(
        Nip04Module(),
        Nip47Module(encryptionSelector),
    )

    private val hasCachedWalletInfo = cachedWalletInfo != null
    private var relayPool: CoroutinesRelayPool? = null
    private var subscriptionHandle: CoroutinesPoolSubscriptionHandle? = null
    private var notificationHandle: CoroutinesPoolSubscriptionHandle? = null
    private var eventCollectorJob: Job? = null
    private var notificationCollectorJob: Job? = null
    private var sessionLifecycleJob: Job? = null

    // Pending requests awaiting responses
    private val pendingMutex = Mutex()
    private val pendingRequests = mutableMapOf<String, PendingRequest>()
    private val pendingMultiRequests = mutableMapOf<String, PendingBatch>()
    private val _pendingRequestCount = MutableStateFlow(0)

    // ========== Public State ==========

    private val _state = MutableStateFlow<NwcClientState>(NwcClientState.Disconnected)

    /**
     * Current state of the client.
     */
    val state: StateFlow<NwcClientState> = _state.asStateFlow()

    private val _walletInfo = MutableStateFlow(cachedWalletInfo ?: WalletInfo.default())

    /**
     * Information about the connected wallet.
     * Updated when the info event is fetched.
     */
    val walletInfo: StateFlow<WalletInfo> = _walletInfo.asStateFlow()

    private val _notifications = MutableSharedFlow<WalletNotification>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Flow of wallet notifications (payment received, payment sent).
     */
    val notifications: SharedFlow<WalletNotification> = _notifications.asSharedFlow()

    /**
     * The credential-bearing URI used by the local convenience factory, or null for injected account capabilities.
     */
    val uri: NwcConnectionUri? get() = connectionUri

    /**
     * Current encryption scheme used for communication.
     */
    val encryption: NwcEncryption get() = _walletInfo.value.preferredEncryption

    /**
     * Number of requests currently pending a response.
     *
     * Useful for UI to show activity indicator or for debugging.
     */
    val pendingRequestCount: Int get() = _pendingRequestCount.value

    init {
        clientJob.invokeOnCompletion {
            _state.value = NwcClientState.Closed
            ownedTransport?.close()
        }
    }

    // ========== Connection Management ==========

    /**
     * Connects to the NWC relay and starts listening for responses.
     *
     * This will:
     * 1. Connect to the relay
     * 2. Fetch the wallet info event
     * 3. Subscribe to response events
     * 4. Optionally subscribe to notifications
     *
     * If the client is already connected or connecting, this call does nothing unless
     * forceReconnect is true.
     *
     * @param forceReconnect If true, forces a new connection even if already connected/connecting.
     * @param connectionTimeoutMs Timeout for the WebSocket connection establishment.
     */
    fun connect(forceReconnect: Boolean = false, connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS) {
        scope.launch {
            lifecycleMutex.withLock {
                val currentState = _state.value
                if (currentState == NwcClientState.Closed) return@withLock
                if (!forceReconnect && (currentState == NwcClientState.Ready || currentState == NwcClientState.Connecting)) {
                    return@withLock
                }
                val previousPool = clearConnectionLocked()
                previousPool?.close()
                failPending(io.github.nicolals.nwc.NwcError.ConnectionError("Connection replaced"))
                _state.value = NwcClientState.Connecting
                val generation = ++connectionGeneration
                val newPool = CoroutinesRelayPool(
                    codec = codec,
                    eventVerifier = verifier,
                    transportFactory = transportFactory,
                    scope = scope,
                    sessionConfig = SessionConfig.Minimal.copy(pingIntervalMillis = PING_INTERVAL_MS),
                    additionalModules = additionalModules,
                )
                relayPool = newPool
                connectionJob = scope.launch {
                    try {
                        connection.relays.map(RelayUrl::parse).distinct().forEach { newPool.addRelay(it) }
                        newPool.connectAll()
                        val connected = withTimeoutOrNull(connectionTimeoutMs) {
                            newPool.connectionStatusFlow.first { statuses ->
                                statuses.values.any { it == ConnectionStatus.Connected }
                            }
                        } != null
                        if (!connected) {
                            setConnectionState(newPool, generation, NwcClientState.Failed(
                                io.github.nicolals.nwc.NwcError.ConnectionError("Failed to connect to any relay")
                            ))
                            return@launch
                        }
                        subscribeToResponses(newPool)
                        if (hasCachedWalletInfo) {
                            if (_walletInfo.value.supportsNotifications) subscribeToNotifications(newPool)
                            setConnectionState(newPool, generation, NwcClientState.Ready)
                        }
                        fetchWalletInfo(newPool)
                        if (_walletInfo.value.supportsNotifications) subscribeToNotifications(newPool)
                        setConnectionState(newPool, generation, NwcClientState.Ready)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setConnectionState(newPool, generation, NwcClientState.Failed(
                            io.github.nicolals.nwc.NwcError.ConnectionError("Failed to initialize wallet connection")
                        ))
                    }
                }
            }
        }
    }

    private suspend fun setConnectionState(pool: CoroutinesRelayPool, generation: Long, state: NwcClientState) {
        lifecycleMutex.withLock {
            if (relayPool !== pool || generation != connectionGeneration || _state.value == NwcClientState.Closed) return
            _state.value = state
            if (state == NwcClientState.Ready && sessionLifecycleJob == null) observeSessionLifecycle(pool)
        }
    }

    private fun observeSessionLifecycle(observedPool: CoroutinesRelayPool) {
        sessionLifecycleJob = scope.launch {
            observedPool.connectionStatusFlow.collect { statuses ->
                if (statuses.isNotEmpty() && statuses.values.none { it == ConnectionStatus.Connected }) {
                    scope.launch { handleSessionDisconnected(observedPool) }
                }
            }
        }
    }

    private suspend fun handleSessionDisconnected(observedPool: CoroutinesRelayPool) {
        val previousPool = lifecycleMutex.withLock {
            if (relayPool !== observedPool || _state.value != NwcClientState.Ready) return
            _state.value = NwcClientState.Disconnected
            ++connectionGeneration
            val previous = clearConnectionLocked()
            failPending(io.github.nicolals.nwc.NwcError.ConnectionError("Connection lost"))
            previous
        }
        previousPool?.close()
    }

    /** Caller must hold lifecycleMutex. */
    private fun clearConnectionLocked(): CoroutinesRelayPool? {
        preparingRequests.forEach { it.cancel(CancellationException("Connection changed")) }
        preparingRequests.clear()
        connectionJob?.cancel()
        connectionJob = null
        sessionLifecycleJob?.cancel()
        sessionLifecycleJob = null
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        notificationCollectorJob?.cancel()
        notificationCollectorJob = null
        subscriptionHandle?.close()
        subscriptionHandle = null
        notificationHandle?.close()
        notificationHandle = null
        return relayPool.also { relayPool = null }
    }

    private suspend fun failPending(error: io.github.nicolals.nwc.NwcError) {
        val requests = pendingMutex.withLock {
            val requests = pendingRequests.values.map(PendingRequest::deferred) +
                pendingMultiRequests.values.flatMap { it.deferreds.values }
            pendingRequests.clear()
            pendingMultiRequests.clear()
            _pendingRequestCount.value = 0
            requests
        }
        requests.forEach { it.completeExceptionally(NwcException(error)) }
    }

    /**
     * Suspends until the client is ready or timeout occurs.
     *
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @return True if ready, false if timeout or connection failed.
     */
    suspend fun awaitReady(timeoutMs: Long = 10_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            _state.first { it == NwcClientState.Ready || it is NwcClientState.Failed || it == NwcClientState.Closed }
        } == NwcClientState.Ready
    }

    /**
     * Closes the client and releases all resources.
     */
    suspend fun close(): Unit = withContext(NonCancellable) {
        val previousPool = lifecycleMutex.withLock {
            if (_state.value == NwcClientState.Closed) return@withContext
            _state.value = NwcClientState.Closed
            ++connectionGeneration
            val previous = clearConnectionLocked()
            failPending(io.github.nicolals.nwc.NwcError.Cancelled())
            previous
        }
        previousPool?.close()
        clientJob.cancelAndJoin()
    }

    // ========== Wallet Operations ==========

    /** Sends a protocol request, including [io.github.nicolals.nostr.nip47.model.NwcUnknownRequest] for wallet extensions. */
    suspend fun request(request: NwcRequest, timeoutMs: Long = 30_000): io.github.nicolals.nwc.NwcResult<NwcResponse> =
        executeRequest(request, timeoutMs)


    /**
     * Gets the current wallet balance.
     */
    suspend fun getBalance(timeoutMs: Long = 30_000): io.github.nicolals.nwc.NwcResult<Amount> {
        return executeRequest(NwcGetBalanceRequest, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcBalanceResult -> Amount.fromMsats(result.balanceMsat)
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Gets detailed wallet information.
     */
    suspend fun getInfo(timeoutMs: Long = 30_000): io.github.nicolals.nwc.NwcResult<WalletDetails> {
        return executeRequest(NwcGetInfoRequest, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcGetInfoResult -> WalletDetails(
                    alias = result.alias,
                    color = result.color,
                    pubkey = result.pubkey,
                    network = result.network,
                    blockHeight = result.blockHeight,
                    blockHash = result.blockHash,
                    methods = result.methods?.mapNotNull { NwcCapability.fromValue(it) }?.toSet() ?: emptySet(),
                    notifications = result.notifications?.mapNotNull { NwcNotificationType.fromValue(it) }?.toSet() ?: emptySet(),
                )
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Pays a BOLT-11 invoice.
     *
     * @param invoice The BOLT-11 invoice string.
     * @param amount Optional amount for zero-amount invoices.
     * @param timeoutMs Timeout in milliseconds (default: 60 seconds).
     * @param verifyOnTimeout If true and the request times out, will attempt to verify
     *        the payment status via lookup_invoice before reporting failure.
     */
    suspend fun payInvoice(
        invoice: String,
        amount: Amount? = null,
        timeoutMs: Long = 60_000,
        verifyOnTimeout: Boolean = true,
    ): io.github.nicolals.nwc.NwcResult<PaymentResult> {
        val request = NwcPayInvoiceRequest(
            invoice = invoice,
            amountMsat = amount?.msats,
        )

        val result = executeRequest(request, timeoutMs)

        return when {
            result.isSuccess -> result.map { response ->
                when (val payResult = response.result) {
                    is NwcPayResult -> PaymentResult(
                        preimage = payResult.preimage,
                        feesPaid = payResult.feesPaidMsat?.let { Amount.fromMsats(it) },
                    )
                    else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
                }
            }
            result.isTimeout && verifyOnTimeout -> {
                // Payment timed out - verify actual status via lookup_invoice
                verifyPaymentOnTimeout(invoice)
            }
            else -> result.map { throw IllegalStateException("Unreachable") }
        }
    }

    /**
     * Verifies payment status after a timeout via lookup_invoice.
     *
     * This is called automatically when payInvoice times out and verifyOnTimeout is true.
     */
    private suspend fun verifyPaymentOnTimeout(invoice: String): io.github.nicolals.nwc.NwcResult<PaymentResult> {
        val verification = lookupInvoice(
            params = LookupInvoiceParams(invoice = invoice),
            timeoutMs = 10_000, // Shorter timeout for verification
        )

        return when {
            verification.isSuccess -> {
                val tx = verification.getOrThrow()
                when (tx.state) {
                    TransactionState.SETTLED -> {
                        // Payment actually succeeded!
                        io.github.nicolals.nwc.NwcResult.success(
                            PaymentResult(
                                preimage = tx.preimage ?: "",
                                feesPaid = tx.feesPaid,
                            )
                        )
                    }
                    TransactionState.PENDING -> {
                        // Still pending - return special status
                        io.github.nicolals.nwc.NwcResult.failure(
                            io.github.nicolals.nwc.NwcError.PaymentPending(
                                message = "Payment sent but not yet confirmed",
                                paymentHash = tx.paymentHash,
                            )
                        )
                    }
                    TransactionState.FAILED, TransactionState.EXPIRED, null -> {
                        // Payment failed, expired, or unknown state
                        io.github.nicolals.nwc.NwcResult.failure(
                            io.github.nicolals.nwc.NwcError.Timeout(
                                message = "Payment timed out and could not be verified"
                            )
                        )
                    }
                }
            }
            else -> {
                // Couldn't verify - return original timeout
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.Timeout(
                        message = "Payment timed out (verification also failed)"
                    )
                )
            }
        }
    }

    /**
     * Pays a BOLT-11 invoice with parameters.
     */
    suspend fun payInvoice(
        params: PayInvoiceParams,
        timeoutMs: Long = 60_000,
        verifyOnTimeout: Boolean = true,
    ): io.github.nicolals.nwc.NwcResult<PaymentResult> =
        payInvoice(params.invoice, params.amount, timeoutMs, verifyOnTimeout)

    /**
     * Sends a keysend payment.
     */
    suspend fun payKeysend(
        params: KeysendParams,
        timeoutMs: Long = 60_000,
    ): io.github.nicolals.nwc.NwcResult<PaymentResult> {
        val request = NwcPayKeysendRequest(
            pubkey = params.pubkey,
            amountMsat = params.amount.msats,
            preimage = params.preimage,
            tlvRecords = params.tlvRecords.map { NwcTlvRecord(it.type, it.value) }.takeIf { it.isNotEmpty() },
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcPayResult -> PaymentResult(
                    preimage = result.preimage,
                    feesPaid = result.feesPaidMsat?.let { Amount.fromMsats(it) },
                )
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Creates a new invoice.
     */
    suspend fun makeInvoice(
        params: MakeInvoiceParams,
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<Transaction> {
        val request = NwcMakeInvoiceRequest(
            amountMsat = params.amount.msats,
            description = params.description,
            descriptionHash = params.descriptionHash,
            expirySeconds = params.expirySeconds,
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcInvoiceResult -> result.transaction.toTransaction()
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Looks up an invoice by payment hash or invoice string.
     */
    suspend fun lookupInvoice(
        params: LookupInvoiceParams,
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<Transaction> {
        val request = NwcLookupInvoiceRequest(
            paymentHash = params.paymentHash,
            invoice = params.invoice,
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcInvoiceResult -> result.transaction.toTransaction()
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Lists transactions matching the given filters.
     */
    suspend fun listTransactions(
        params: ListTransactionsParams = ListTransactionsParams(),
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<List<Transaction>> {
        val request = NwcListTransactionsRequest(
            from = params.from,
            until = params.until,
            limit = params.limit?.toLong(),
            offset = params.offset?.toLong(),
            unpaid = params.unpaid,
            type = params.type?.value,
        )
        return executeRequest(request, timeoutMs).map { response ->
            when (val result = response.result) {
                is NwcListTransactionsResult -> result.transactions.map { it.toTransaction() }
                else -> throw IllegalStateException("Unexpected result type: ${response.resultType}")
            }
        }
    }

    /**
     * Pays multiple invoices in a batch.
     */
    suspend fun multiPayInvoice(
        items: List<MultiPayInvoiceItem>,
        timeoutMs: Long = 120_000,
    ): io.github.nicolals.nwc.NwcResult<Map<String, MultiPayItemResult>> {
        val request = NwcMultiPayInvoiceRequest(
            invoices = items.map { NwcInvoiceRequestItem(it.invoice, it.amount?.msats, null, it.id) }
        )
        return executeMultiRequest(request, items.map { it.id }, timeoutMs).map { responses ->
            responses.mapValues { (_, response) ->
                val responseError = response.error
                if (responseError != null) {
                    MultiPayItemResult.Failed(responseError.toNwcError())
                } else {
                    when (val result = response.result) {
                        is NwcPayResult -> MultiPayItemResult.Success(
                            PaymentResult(result.preimage, result.feesPaidMsat?.let { Amount.fromMsats(it) })
                        )
                        else -> MultiPayItemResult.Failed(
                            io.github.nicolals.nwc.NwcError.ProtocolError("Unexpected result type")
                        )
                    }
                }
            }
        }
    }

    /**
     * Sends multiple keysend payments in a batch.
     */
    suspend fun multiPayKeysend(
        items: List<MultiKeysendItem>,
        timeoutMs: Long = 120_000,
    ): io.github.nicolals.nwc.NwcResult<Map<String, MultiPayItemResult>> {
        val request = NwcMultiPayKeysendRequest(
            keysends = items.map {
                NwcKeysendRequestItem(
                    pubkey = it.pubkey,
                    amountMsat = it.amount.msats,
                    preimage = it.preimage,
                    tlvRecords = it.tlvRecords.map { tlv -> NwcTlvRecord(tlv.type, tlv.value) }.takeIf { list -> list.isNotEmpty() },
                    id = it.id,
                )
            }
        )
        return executeMultiRequest(request, items.map { it.id }, timeoutMs).map { responses ->
            responses.mapValues { (_, response) ->
                val responseError = response.error
                if (responseError != null) {
                    MultiPayItemResult.Failed(responseError.toNwcError())
                } else {
                    when (val result = response.result) {
                        is NwcPayResult -> MultiPayItemResult.Success(
                            PaymentResult(result.preimage, result.feesPaidMsat?.let { Amount.fromMsats(it) })
                        )
                        else -> MultiPayItemResult.Failed(
                            io.github.nicolals.nwc.NwcError.ProtocolError("Unexpected result type")
                        )
                    }
                }
            }
        }
    }

    /**
     * Checks the status of a previously initiated payment.
     *
     * Useful after app restart to resolve payments that were in-flight.
     * This looks up the invoice by payment hash to get its current state.
     *
     * @param paymentHash The payment hash to check.
     * @param timeoutMs Timeout for the lookup.
     * @return The transaction details including current state.
     */
    suspend fun checkPaymentStatus(
        paymentHash: String,
        timeoutMs: Long = 30_000,
    ): io.github.nicolals.nwc.NwcResult<Transaction> {
        return lookupInvoice(
            params = LookupInvoiceParams(paymentHash = paymentHash),
            timeoutMs = timeoutMs,
        )
    }

    /**
     * Refreshes the wallet info event from the relay.
     */
    suspend fun refreshWalletInfo(): io.github.nicolals.nwc.NwcResult<WalletInfo> {
        val currentPool = relayPool ?: return io.github.nicolals.nwc.NwcResult.failure(
            io.github.nicolals.nwc.NwcError.ConnectionError("Not connected")
        )
        return try {
            fetchWalletInfo(currentPool)
            io.github.nicolals.nwc.NwcResult.success(_walletInfo.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Failed to fetch wallet info")
            )
        }
    }

    // ========== Internal Methods ==========

    private suspend fun fetchWalletInfo(pool: CoroutinesRelayPool) {
        val filter = Filter(
            kinds = listOf(NwcInfoEvent.KIND),
            authors = listOf(connection.walletPubkey),
            limit = 1,
        )

        val handle = pool.subscribe(listOf(filter))
        try {
            val event = withTimeoutOrNull(5_000L) {
                var result: Event? = null
                handle.events.first { event ->
                    if (event.kind == NwcInfoEvent.KIND && isAuthenticWalletEvent(event)) {
                        result = event
                        true
                    } else {
                        false
                    }
                }
                result
            }

            if (event != null) {
                val infoEvent = NwcInfoEvent.fromEventOrNull(event)
                if (infoEvent != null) {
                    lifecycleMutex.withLock {
                        if (relayPool === pool && _state.value != NwcClientState.Closed) {
                            _walletInfo.value = WalletInfo.fromInfoEvent(infoEvent)
                        }
                    }
                }
            }
        } finally {
            handle.close()
        }
    }

    private suspend fun subscribeToResponses(pool: CoroutinesRelayPool) {
        val filter = Filter(
            kinds = listOf(NwcResponseEvent.KIND),
            authors = listOf(connection.walletPubkey),
            tagFilters = mapOf("p" to listOf(connection.clientPubkey.hex)),
        )

        val handle = pool.subscribe(listOf(filter))
        lifecycleMutex.withLock {
            if (relayPool !== pool || _state.value == NwcClientState.Closed) {
                handle.close()
                return
            }
            subscriptionHandle = handle
            eventCollectorJob = scope.launch {
                handle.events.collect { event -> handleResponseEvent(event) }
            }
        }
    }

    private suspend fun subscribeToNotifications(pool: CoroutinesRelayPool) {
        val nip44Kind = NwcNotificationEvent.KIND
        val nip04Kind = NwcNotificationEvent.LEGACY_KIND

        val filter = Filter(
            kinds = listOf(nip44Kind, nip04Kind),
            authors = listOf(connection.walletPubkey),
            tagFilters = mapOf("p" to listOf(connection.clientPubkey.hex)),
        )

        val handle = pool.subscribe(listOf(filter))
        lifecycleMutex.withLock {
            if (relayPool !== pool || _state.value == NwcClientState.Closed || notificationHandle != null) {
                handle.close()
                return
            }
            notificationHandle = handle
            notificationCollectorJob = scope.launch {
                handle.events.collect { event -> handleNotificationEvent(event) }
            }
        }
    }

    private fun isAuthenticWalletEvent(event: Event, addressedToClient: Boolean = false): Boolean {
        if (event.pubkey != connection.walletPubkey) return false
        if (addressedToClient && event.tags.filter { it.firstOrNull() == "p" }.singleOrNull()?.getOrNull(1) != signer.pubkey.hex) return false
        return verifier.verify(event) is CryptoResult.Ok
    }

    private suspend fun handleResponseEvent(event: Event) {
        if (!isAuthenticWalletEvent(event, addressedToClient = true)) return
        val requestId = event.tags.filter { it.firstOrNull() == "e" }.singleOrNull()?.getOrNull(1) ?: return
        val expectedEncryption = pendingMutex.withLock {
            pendingRequests[requestId]?.encryption ?: pendingMultiRequests[requestId]?.encryption
        } ?: return
        val encryptionTags = event.tags.filter { it.firstOrNull() == "encryption" }
        if (encryptionTags.isNotEmpty() &&
            encryptionTags.singleOrNull()?.takeIf { it.size == 2 }?.get(1) != expectedEncryption.tag
        ) return
        val encryptionSelector = NwcEncryptionSelector { expectedEncryption }
        val responseEvent = NwcResponseEvent.fromEventOrNull(event, encryptionSelector) ?: return

        // Decrypt the response
        val response = when (val result = responseEvent.decryptForClient(codec, cipher, encryptionOverride = expectedEncryption)) {
            is CryptoResult.Ok -> result.value
            is CryptoResult.Err -> {
                // Invalid encrypted responses cannot complete a pending request.
                return
            }
        }

        currentCoroutineContext().ensureActive()
        val responseId = responseEvent.responseId

        scope.launch {
            if (responseId == null) {
                val pending = pendingMutex.withLock {
                    val candidate = pendingRequests[requestId]
                    if (
                        !isExactResponseMatch(
                            responseRequestId = requestId,
                            pendingRequestId = requestId.takeIf { candidate != null },
                            expectedMethod = candidate?.expectedMethod,
                            resultType = response.resultType
                        )
                    ) {
                        null
                    } else {
                        pendingRequests.remove(requestId).also {
                            _pendingRequestCount.value = pendingRequests.size
                        }
                    }
                }
                pending?.deferred?.complete(response)
            } else {
                val deferred = pendingMutex.withLock {
                    pendingMultiRequests[requestId]?.takeIf { it.expectedMethod == response.resultType }
                        ?.deferreds?.remove(responseId)
                }
                deferred?.complete(response)
            }
        }
    }

    private suspend fun handleNotificationEvent(event: Event) {
        if (!isAuthenticWalletEvent(event, addressedToClient = true)) return
        val notificationEvent = NwcNotificationEvent.fromEventOrNull(event) ?: return

        // Decrypt the notification
        val notification = when (val result = notificationEvent.decryptForClient(codec, cipher)) {
            is CryptoResult.Ok -> result.value
            is CryptoResult.Err -> return
        }

        currentCoroutineContext().ensureActive()
        val walletNotification = when (notification) {
            is NwcPaymentReceivedNotification -> WalletNotification.PaymentReceived(
                notification.transaction.toTransaction()
            )
            is NwcPaymentSentNotification -> WalletNotification.PaymentSent(
                notification.transaction.toTransaction()
            )
            else -> return
        }

        _notifications.tryEmit(walletNotification)
    }

    private suspend fun ensureConnected(timeoutMs: Long): Boolean {
        // If already ready, return immediately
        if (_state.value == NwcClientState.Ready) return true
        if (_state.value == NwcClientState.Closed) return false

        // Retry connection establishment with exponential backoff.
        // This handles transient failures (DNS hiccups, relay temporarily unreachable,
        // packet loss during handshake, iOS network stack issues after backgrounding, etc.)
        // without requiring auto-reconnection at the session level (which wouldn't help
        // recover in-flight NWC requests anyway since events are ephemeral).
        val timeMark = TimeSource.Monotonic.markNow()
        var attempt = 0

        while (true) {
            attempt++
            val elapsed = timeMark.elapsedNow().inWholeMilliseconds
            val remaining = timeoutMs - elapsed

            // Check if we've exhausted our timeout budget
            if (remaining <= 0) return false

            // If disconnected or failed, trigger connection with appropriate timeout
            val currentState = _state.value
            if (currentState == NwcClientState.Disconnected || currentState is NwcClientState.Failed) {
                // Use shorter per-attempt timeout to leave room for retries
                val perAttemptTimeout = minOf(remaining, CONNECTION_ATTEMPT_TIMEOUT_MS)
                connect(forceReconnect = true, connectionTimeoutMs = perAttemptTimeout)
            }

            // Wait for Ready state
            val waitTimeout = minOf(remaining, CONNECTION_ATTEMPT_TIMEOUT_MS + WALLET_INFO_TIMEOUT_MS)
            val ready = try {
                withTimeoutOrNull(waitTimeout) {
                    _state.first { it == NwcClientState.Ready || it is NwcClientState.Failed || it == NwcClientState.Closed }
                } == NwcClientState.Ready
            } catch (e: CancellationException) {
                throw e
            }

            if (ready) return true

            // Connection failed - check if we should retry
            val newElapsed = timeMark.elapsedNow().inWholeMilliseconds
            val newRemaining = timeoutMs - newElapsed

            // Need minimum time for another attempt, and respect max attempts
            if (newRemaining < MIN_RETRY_BUDGET_MS || attempt >= MAX_CONNECTION_ATTEMPTS) {
                return false
            }

            // Exponential backoff: 500ms, 1000ms, 2000ms, capped at 3000ms
            val backoffMs = minOf(
                CONNECTION_BACKOFF_BASE_MS * (1L shl (attempt - 1)),
                CONNECTION_BACKOFF_MAX_MS
            )
            // Don't backoff longer than remaining time minus minimum for next attempt
            val actualBackoff = minOf(backoffMs, newRemaining - MIN_RETRY_BUDGET_MS)
            if (actualBackoff > 0) {
                delay(actualBackoff)
            }
        }
    }

    private suspend fun <T> executeWithDeadline(
        timeoutMs: Long,
        block: suspend (UnixTimeSeconds) -> io.github.nicolals.nwc.NwcResult<T>,
    ): io.github.nicolals.nwc.NwcResult<T> {
        if (timeoutMs <= 0) return io.github.nicolals.nwc.NwcResult.failure(
            io.github.nicolals.nwc.NwcError.ProtocolError("timeoutMs must be positive")
        )
        val expiration = UnixTimeSeconds(requestExpirationSeconds(UnixTimeSeconds.now().value, timeoutMs))
        val operation = scope.async {
            try {
                withTimeoutOrNull(timeoutMs) {
                    val job = requireNotNull(currentCoroutineContext()[Job])
                    try {
                        block(expiration)
                    } finally {
                        withContext(NonCancellable) { lifecycleMutex.withLock { preparingRequests.remove(job) } }
                    }
                }
                    ?: io.github.nicolals.nwc.NwcResult.failure(
                        io.github.nicolals.nwc.NwcError.Timeout("Request deadline exceeded", timeoutMs)
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ProtocolError("Request capability failed")
                )
            }
        }
        return try {
            operation.await()
        } finally {
            withContext(NonCancellable) { operation.cancelAndJoin() }
        }
    }

    private suspend fun executeRequest(
        request: NwcRequest,
        timeoutMs: Long,
    ): io.github.nicolals.nwc.NwcResult<NwcResponse> = executeWithDeadline(timeoutMs) { expiration ->
        executeRequestWithinDeadline(request, timeoutMs, expiration)
    }

    private suspend fun executeRequestWithinDeadline(
        request: NwcRequest,
        timeoutMs: Long,
        expiration: UnixTimeSeconds,
    ): io.github.nicolals.nwc.NwcResult<NwcResponse> {
        // Ensure we are connected before proceeding
        // Give generous time for connection establishment with retries.
        // For short timeouts, use up to half; for longer timeouts, cap at 15s to leave
        // enough time for the actual request/response cycle.
        val connectionTimeout = when {
            timeoutMs <= 10_000L -> timeoutMs / 2
            timeoutMs <= 30_000L -> minOf(15_000L, timeoutMs / 2)
            else -> 15_000L
        }
        if (!ensureConnected(connectionTimeout)) {
            val state = _state.value
            val message = if (state is NwcClientState.Failed) {
                state.error.message
            } else {
                "Failed to establish connection to relay"
            }
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError(message)
            )
        }

        val currentPool = relayPool ?: return io.github.nicolals.nwc.NwcResult.failure(
            io.github.nicolals.nwc.NwcError.ConnectionError("Not connected")
        )

        if (_state.value != NwcClientState.Ready) {
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Client not ready")
            )
        }

        if (currentPool.connectionStatusFlow.value.values.none {
                it == ConnectionStatus.Connected
            }
        ) {
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Connection lost")
            )
        }

        val preparationJob = requireNotNull(currentCoroutineContext()[Job])
        val generation = lifecycleMutex.withLock {
            if (relayPool !== currentPool || _state.value != NwcClientState.Ready) {
                return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ConnectionError("Connection changed before request preparation")
                )
            }
            preparingRequests += preparationJob
            connectionGeneration
        }
        // Create and sign the request event
        val encryption = _walletInfo.value.preferredEncryption
        val templateResult = NwcRequestEvent.template(
            walletPubkey = connection.walletPubkey,
            request = request,
            createdAt = UnixTimeSeconds.now(),
            codec = codec,
            cipher = cipher,
            encryption = encryption,
            expiration = expiration,
        )

        val template = when (templateResult) {
            is CryptoResult.Ok -> templateResult.value
            is CryptoResult.Err -> return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.CryptoError("Failed to encrypt request")
            )
        }

        val signedEvent = when (val result = signer.sign(template)) {
            is CryptoResult.Ok -> result.value
            is CryptoResult.Err -> return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.CryptoError("Failed to sign request")
            )
        }
        currentCoroutineContext().ensureActive()
        val requestId = signedEvent.event.id.hex

        val deferred = CompletableDeferred<NwcResponse>(clientJob)
        return try {
            pendingMutex.withLock {
                pendingRequests[requestId] = PendingRequest(request.method, encryption, deferred)
                _pendingRequestCount.value = pendingRequests.size
            }
            val publishError = awaitPublication(currentPool, generation, signedEvent.event) { deferred.isCompleted }
            if (publishError != null) return io.github.nicolals.nwc.NwcResult.failure(publishError)
            val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
            when {
                response == null -> io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.Timeout("Timeout waiting for wallet response", timeoutMs)
                )
                response.error != null -> io.github.nicolals.nwc.NwcResult.failure(requireNotNull(response.error).toNwcError())
                response.result == null -> io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ProtocolError("Wallet response has no result")
                )
                else -> io.github.nicolals.nwc.NwcResult.success(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NwcException) {
            // Once publication is attempted, a missing response does not establish payment failure.
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.Timeout("Connection lost while waiting for response (request may have been sent)", timeoutMs)
            )
        } catch (e: Exception) {
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ProtocolError("Request failed")
            )
        } finally {
            withContext(NonCancellable) {
                removePendingRequest(requestId)
                deferred.cancel()
            }
        }
    }

    private suspend fun awaitPublication(
        pool: CoroutinesRelayPool,
        generation: Long,
        event: Event,
        hasResponse: () -> Boolean,
    ): io.github.nicolals.nwc.NwcError? {
        val preparationJob = requireNotNull(currentCoroutineContext()[Job])
        val handle = lifecycleMutex.withLock {
            preparingRequests.remove(preparationJob)
            if (relayPool !== pool || connectionGeneration != generation || _state.value != NwcClientState.Ready) {
                return io.github.nicolals.nwc.NwcError.ConnectionError("Connection changed before publication")
            }
            pool.publish(event)
        }
        try {
            var mayHaveBeenSent = handle.status.values.any {
                it == PublishStatus.Pending || it is PublishStatus.Accepted
            }
            val statuses = withTimeoutOrNull(PUBLISH_ACK_TIMEOUT_MS) {
                handle.statusFlow.first { statuses ->
                    if (statuses.values.any { it == PublishStatus.Pending || it is PublishStatus.Accepted }) {
                        mayHaveBeenSent = true
                    }
                    statuses.values.any { it is PublishStatus.Accepted } || statuses.values.all { it.isTerminal }
                }
            }
            if (statuses?.values?.any { it is PublishStatus.Accepted } == true || hasResponse()) return null
            return if (mayHaveBeenSent) {
                io.github.nicolals.nwc.NwcError.Timeout("Request may have been published without acknowledgment", PUBLISH_ACK_TIMEOUT_MS)
            } else {
                io.github.nicolals.nwc.NwcError.ConnectionError("Request not sent: no relay accepted publication")
            }
        } finally {
            handle.close()
        }
    }

    private suspend fun executeMultiRequest(
        request: NwcRequest,
        itemIds: List<String>,
        timeoutMs: Long,
    ): io.github.nicolals.nwc.NwcResult<Map<String, NwcResponse>> = executeWithDeadline(timeoutMs) { expiration ->
        executeMultiRequestWithinDeadline(request, itemIds, timeoutMs, expiration)
    }

    private suspend fun executeMultiRequestWithinDeadline(
        request: NwcRequest,
        itemIds: List<String>,
        timeoutMs: Long,
        expiration: UnixTimeSeconds,
    ): io.github.nicolals.nwc.NwcResult<Map<String, NwcResponse>> {
        // Ensure we are connected before proceeding
        // Give generous time for connection establishment with retries
        val connectionTimeout = when {
            timeoutMs <= 10_000L -> timeoutMs / 2
            timeoutMs <= 30_000L -> minOf(15_000L, timeoutMs / 2)
            else -> 15_000L
        }
        if (!ensureConnected(connectionTimeout)) {
            val state = _state.value
            val message = if (state is NwcClientState.Failed) {
                state.error.message
            } else {
                "Failed to establish connection to relay"
            }
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError(message)
            )
        }

        val currentPool = relayPool ?: return io.github.nicolals.nwc.NwcResult.failure(
            io.github.nicolals.nwc.NwcError.ConnectionError("Not connected")
        )

        if (_state.value != NwcClientState.Ready) {
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Client not ready")
            )
        }

        if (currentPool.connectionStatusFlow.value.values.none {
                it == ConnectionStatus.Connected
            }
        ) {
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ConnectionError("Connection lost")
            )
        }

        val preparationJob = requireNotNull(currentCoroutineContext()[Job])
        val generation = lifecycleMutex.withLock {
            if (relayPool !== currentPool || _state.value != NwcClientState.Ready) {
                return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ConnectionError("Connection changed before request preparation")
                )
            }
            preparingRequests += preparationJob
            connectionGeneration
        }
        // Create and sign the request event
        val encryption = _walletInfo.value.preferredEncryption
        val templateResult = NwcRequestEvent.template(
            walletPubkey = connection.walletPubkey,
            request = request,
            createdAt = UnixTimeSeconds.now(),
            codec = codec,
            cipher = cipher,
            encryption = encryption,
            expiration = expiration,
        )

        val template = when (templateResult) {
            is CryptoResult.Ok -> templateResult.value
            is CryptoResult.Err -> return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.CryptoError("Failed to encrypt request")
            )
        }

        val signedEvent = when (val result = signer.sign(template)) {
            is CryptoResult.Ok -> result.value
            is CryptoResult.Err -> return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.CryptoError("Failed to sign request")
            )
        }
        currentCoroutineContext().ensureActive()
        val requestId = signedEvent.event.id.hex

        if (itemIds.distinct().size != itemIds.size) {
            return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ProtocolError("Batch item IDs must be unique")
            )
        }
        // Keep the complete batch stable while the correlation index removes resolved entries.
        val deferreds = mutableMapOf<String, CompletableDeferred<NwcResponse>>()
        itemIds.forEach { id ->
            deferreds[id] = CompletableDeferred(clientJob)
        }
        return try {
            pendingMutex.withLock {
                pendingMultiRequests[requestId] = PendingBatch(request.method, encryption, deferreds.toMutableMap())
            }
            val publishError = awaitPublication(currentPool, generation, signedEvent.event) { deferreds.values.any { it.isCompleted } }
            if (publishError != null) return io.github.nicolals.nwc.NwcResult.failure(publishError)
            val responses = withTimeoutOrNull(timeoutMs) {
                buildMap {
                    for ((id, deferred) in deferreds) put(id, deferred.await())
                }
            }
            if (responses == null) {
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.Timeout("Timeout waiting for wallet responses", timeoutMs)
                )
            } else {
                io.github.nicolals.nwc.NwcResult.success(responses)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NwcException) {
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.Timeout("Connection lost while waiting for responses (request may have been sent)", timeoutMs)
            )
        } catch (e: Exception) {
            io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ProtocolError("Batch request failed")
            )
        } finally {
            withContext(NonCancellable) {
                removePendingMultiRequest(requestId)
                deferreds.values.forEach { it.cancel() }
            }
        }
    }

    private suspend fun removePendingRequest(requestId: String) {
        pendingMutex.withLock {
            pendingRequests.remove(requestId)
            _pendingRequestCount.value = pendingRequests.size
        }
    }

    private suspend fun removePendingMultiRequest(requestId: String) {
        pendingMutex.withLock {
            pendingMultiRequests.remove(requestId)
        }
    }

    // ========== Factory ==========

    companion object {
        private fun defaultSigner(uri: NwcConnectionUri): EventTemplateSigner =
            when (val result = NostrSigner.fromSecretKey(SecretKey.fromBytes(uri.secret))) {
                is CryptoResult.Ok -> result.value
                is CryptoResult.Err -> error("Failed to create NWC signer")
            }

        private fun defaultCipher(uri: NwcConnectionUri): NwcCipher {
            val secret = SecretKey.fromBytes(uri.secret)
            return Nip47Cipher(LocalNip04Cipher(secret, NostrCrypto), LocalNip44Cipher(secret, NostrCrypto))
        }

        /** Timeout for waiting for relay to acknowledge publish (OK message) */
        private const val PUBLISH_ACK_TIMEOUT_MS = 5_000L
        /** Default timeout for WebSocket connection establishment (DNS + TCP + TLS + WS handshake) */
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L

        /**
         * Timeout per connection attempt (shorter to allow retries).
         * 7s is generous for mobile networks where DNS and TLS can be slow.
         */
        private const val CONNECTION_ATTEMPT_TIMEOUT_MS = 7_000L

        /** Timeout for fetching wallet info after connection */
        private const val WALLET_INFO_TIMEOUT_MS = 5_000L

        /**
         * Maximum number of connection attempts before giving up.
         * 3 attempts with exponential backoff provides good balance between
         * resilience and responsiveness for payment apps.
         *
         * Timing with 15s budget:
         * - Attempt 1: immediate (up to 7s)
         * - Attempt 2: after 500ms backoff (up to 7s)
         * - Attempt 3: after 1000ms backoff (up to remaining time)
         */
        private const val MAX_CONNECTION_ATTEMPTS = 3

        /** Base delay for exponential backoff between connection attempts */
        private const val CONNECTION_BACKOFF_BASE_MS = 500L

        /** Maximum backoff delay between connection attempts */
        private const val CONNECTION_BACKOFF_MAX_MS = 3_000L

        /**
         * Minimum time budget required to attempt another connection retry.
         * Should be slightly less than CONNECTION_ATTEMPT_TIMEOUT_MS to allow
         * a meaningful attempt.
         */
        private const val MIN_RETRY_BUDGET_MS = 4_000L

        /** Interval for WebSocket ping to detect dead connections (10 seconds) */
        private const val PING_INTERVAL_MS = 10_000L

        /**
         * Creates a new NWC client from a connection URI.
         *
         * @param uri The NWC connection URI (or the string to parse).
         * @param scope CoroutineScope for the client's operations.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param cachedWalletInfo Optional cached wallet info to use before fetching from relay.
         * @param transportFactory Optional caller-owned transport; cannot be combined with httpClient.
         * @param codec JSON and wire codec for the client and its relay sessions.
         * @param signer Signing capability for the connection identity; may suspend for user approval.
         * @param verifier Authenticates incoming event IDs and signatures.
         * @param cipher Encryption capability for the same identity; supports remote implementations.
         */
        operator fun invoke(
            uri: NwcConnectionUri,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            cachedWalletInfo: WalletInfo? = null,
            transportFactory: RelayTransportFactory? = null,
            codec: NostrJsonCodec = KotlinxNostrJsonCodec(),
            signer: EventTemplateSigner = defaultSigner(uri),
            verifier: EventVerifier = NostrEventVerifier(),
            cipher: NwcCipher = defaultCipher(uri),
        ): NwcClient {
            val connection = NwcConnection(uri.walletPubkey, uri.clientPubkey, uri.relays)
            require(signer.pubkey == connection.clientPubkey) { "Signer public key must match the NWC connection" }
            require(httpClient == null || transportFactory == null) {
                "Supply either httpClient or transportFactory"
            }
            val factory = transportFactory ?: if (httpClient == null) {
                KtorRelayTransportFactory()
            } else {
                KtorRelayTransportFactory(httpClient)
            }
            val client = NwcClient(
                connection, uri, scope, factory, codec, signer, verifier, cipher,
                if (transportFactory == null) factory as? AutoCloseable else null,
                cachedWalletInfo,
            )
            // Automatically start connecting when the client is created.
            // This allows connection setup to run in parallel with UI/app logic.
            // Operations will wait for connection if needed via ensureConnected().
            client.connect()
            return client
        }

        /**
         * Creates a client from public connection details and account capabilities.
         * No private key or credential-bearing URI is required. Injected transport resources
         * remain caller-owned; the default Ktor factory is owned by this client.
         */
        operator fun invoke(
            connection: NwcConnection,
            scope: CoroutineScope,
            signer: EventTemplateSigner,
            cipher: NwcCipher,
            transportFactory: RelayTransportFactory? = null,
            codec: NostrJsonCodec = KotlinxNostrJsonCodec(),
            verifier: EventVerifier = NostrEventVerifier(),
            cachedWalletInfo: WalletInfo? = null,
        ): NwcClient {
            require(signer.pubkey == connection.clientPubkey) { "Signer public key must match the NWC connection" }
            val factory = transportFactory ?: KtorRelayTransportFactory()
            return NwcClient(
                connection, null, scope, factory, codec, signer, verifier, cipher,
                if (transportFactory == null) factory as? AutoCloseable else null,
                cachedWalletInfo,
            ).also { it.connect() }
        }

        /**
         * Creates a new NWC client from a connection URI string.
         *
         * @param uriString The NWC connection URI string.
         * @param scope CoroutineScope for the client's operations.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param cachedWalletInfo Optional cached wallet info to use before fetching from relay.
         * @return The NWC client, or null if the URI is invalid.
         */
        fun fromUri(
            uriString: String,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            cachedWalletInfo: WalletInfo? = null,
        ): NwcClient? {
            val uri = NwcConnectionUri.parse(uriString) ?: return null
            return invoke(uri, scope, httpClient, cachedWalletInfo)
        }

        /**
         * Creates a new NWC client from a deep link URI.
         *
         * Handles both direct NWC URIs and callback URIs from wallet apps.
         *
         * @param deepLinkUri The deep link URI.
         * @param scope CoroutineScope for the client's operations.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param cachedWalletInfo Optional cached wallet info to use before fetching from relay.
         * @return The NWC client, or null if the URI is invalid.
         */
        fun fromDeepLink(
            deepLinkUri: String,
            scope: CoroutineScope,
            httpClient: HttpClient? = null,
            cachedWalletInfo: WalletInfo? = null,
        ): NwcClient? {
            val uri = NwcConnectionUri.parseDeepLink(deepLinkUri) ?: return null
            return invoke(uri, scope, httpClient, cachedWalletInfo)
        }

        /**
         * Discovers wallet capabilities without maintaining a long-running connection.
         *
         * This is useful for wallet setup flows where you need to:
         * 1. Validate the URI
         * 2. Fetch wallet capabilities
         * 3. Show user what they're connecting to
         * 4. Store the connection if they approve
         *
         * The client is automatically closed after discovery completes.
         *
         * @param uri The NWC connection URI string.
         * @param httpClient Optional HttpClient for the Ktor transport.
         * @param timeoutMs Timeout for the entire discovery operation.
         * @return Discovery result with wallet info and details, or failure.
         */
        suspend fun discover(
            uri: String,
            httpClient: HttpClient? = null,
            timeoutMs: Long = 10_000,
            transportFactory: RelayTransportFactory? = null,
        ): io.github.nicolals.nwc.NwcResult<WalletDiscovery> {
            if (timeoutMs <= 0) return io.github.nicolals.nwc.NwcResult.failure(
                io.github.nicolals.nwc.NwcError.ProtocolError("timeoutMs must be positive")
            )
            val parsed = NwcConnectionUri.parse(uri)
                ?: return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ProtocolError("Invalid NWC URI")
                )

            val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
            val client = invoke(parsed, scope, httpClient, transportFactory = transportFactory)

            return try {
                withTimeoutOrNull(timeoutMs) {
                    if (!client.awaitReady(timeoutMs)) {
                        return@withTimeoutOrNull io.github.nicolals.nwc.NwcResult.failure(
                            io.github.nicolals.nwc.NwcError.ConnectionError("Failed to connect to relay")
                        )
                    }
                    val walletInfo = client.walletInfo.value
                    val details = client.getInfo(timeoutMs).getOrNull()
                    io.github.nicolals.nwc.NwcResult.success(WalletDiscovery(parsed, walletInfo, details))
                } ?: io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.Timeout("Wallet discovery deadline exceeded", timeoutMs)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ConnectionError("Wallet discovery failed")
                )
            } finally {
                client.close()
                scope.cancel()
            }
        }

        /**
         * Discovers wallet capabilities from a deep link URI.
         *
         * @see discover
         */
        suspend fun discoverFromDeepLink(
            deepLinkUri: String,
            httpClient: HttpClient? = null,
            timeoutMs: Long = 10_000,
        ): io.github.nicolals.nwc.NwcResult<WalletDiscovery> {
            val nwcUri = NwcConnectionUri.parseDeepLink(deepLinkUri)?.raw
                ?: return io.github.nicolals.nwc.NwcResult.failure(
                    io.github.nicolals.nwc.NwcError.ProtocolError("Invalid deep link URI")
                )
            return discover(nwcUri, httpClient, timeoutMs)
        }
    }
}

internal fun isExactResponseMatch(
    responseRequestId: String?,
    pendingRequestId: String?,
    expectedMethod: String?,
    resultType: String
): Boolean =
    responseRequestId != null &&
        responseRequestId == pendingRequestId &&
        expectedMethod == resultType

internal fun requestExpirationSeconds(nowSeconds: Long, timeoutMs: Long): Long {
    require(timeoutMs > 0) { "timeoutMs must be positive" }
    val seconds = timeoutMs / 1_000L + if (timeoutMs % 1_000L == 0L) 0L else 1L
    val lifetime = seconds + 5L
    return if (nowSeconds > Long.MAX_VALUE - lifetime) Long.MAX_VALUE else nowSeconds + lifetime
}

private data class PendingBatch(
    val expectedMethod: String,
    val encryption: NwcEncryption,
    val deferreds: MutableMap<String, CompletableDeferred<NwcResponse>>,
)

private data class PendingRequest(
    val expectedMethod: String,
    val encryption: NwcEncryption,
    val deferred: CompletableDeferred<NwcResponse>,
)

/**
 * Result of wallet discovery containing all information needed to display
 * to the user and persist the connection.
 */
data class WalletDiscovery(
    /**
     * The parsed connection URI.
     */
    val uri: NwcConnectionUri,

    /**
     * Wallet info from the info event (capabilities, notifications, encryption).
     */
    val walletInfo: WalletInfo,

    /**
     * Detailed wallet info from get_info (alias, network, color, etc.).
     * May be null if get_info is not supported or failed.
     */
    val details: WalletDetails?,
) {
    /** Human-readable name for the wallet/node. */
    val alias: String? get() = details?.alias

    /** Bitcoin network (mainnet, testnet, etc.). */
    val network: String? get() = details?.network

    /** Node color (hex string). */
    val color: String? get() = details?.color

    /** Set of capabilities supported by the wallet. */
    val capabilities: Set<NwcCapability> get() = walletInfo.capabilities

    /** Whether the wallet supports pay_invoice. */
    val supportsPayInvoice: Boolean get() = NwcCapability.PAY_INVOICE in capabilities

    /** Whether the wallet supports make_invoice. */
    val supportsMakeInvoice: Boolean get() = NwcCapability.MAKE_INVOICE in capabilities

    /** Whether the wallet supports get_balance. */
    val supportsGetBalance: Boolean get() = NwcCapability.GET_BALANCE in capabilities

    /** Whether the wallet supports list_transactions. */
    val supportsListTransactions: Boolean get() = NwcCapability.LIST_TRANSACTIONS in capabilities

    /** Preferred encryption scheme. */
    val preferredEncryption: io.github.nicolals.nostr.nip47.model.NwcEncryption
        get() = walletInfo.preferredEncryption

    /** Whether encryption defaulted to NIP-04 (wallet didn't advertise encryption). */
    val encryptionDefaultedToNip04: Boolean get() = walletInfo.encryptionDefaultedToNip04
}

/**
 * State of the NWC client.
 */
sealed class NwcClientState {
    /** Client is disconnected. */
    data object Disconnected : NwcClientState()

    /** Client is connecting to the relay. */
    data object Connecting : NwcClientState()

    /** Client is connected and ready for operations. */
    data object Ready : NwcClientState()

    /** Client has been closed. */
    data object Closed : NwcClientState()

    /** Connection failed with an error. */
    data class Failed(val error: io.github.nicolals.nwc.NwcError) : NwcClientState()
}

// ========== Extension Functions ==========

private fun NwcTransaction.toTransaction(): Transaction = Transaction(
    type = type?.let { TransactionType.fromValue(it) } ?: TransactionType.INCOMING,
    state = state?.let { TransactionState.fromValue(it) },
    paymentHash = paymentHash ?: "",
    amount = Amount.fromMsats(amountMsat ?: 0),
    invoice = invoice,
    description = description,
    descriptionHash = descriptionHash,
    preimage = preimage,
    feesPaid = feesPaidMsat?.let { Amount.fromMsats(it) },
    createdAt = createdAt ?: 0,
    expiresAt = expiresAt,
    settledAt = settledAt,
)

private fun NwcError.toNwcError(): io.github.nicolals.nwc.NwcError =
    io.github.nicolals.nwc.NwcError.WalletError(NwcErrorCode.fromCode(code), message)
