package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgradeRegistry
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.message.view.RelayMessageView
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.codec.NostrJsonCodec
import io.github.nicolals.nostr.core.crypto.EventVerifier
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.nip01.message.RelayEventMessage
import kotlinx.coroutines.flow.Flow
import io.github.nicolals.nostr.core.nip.NipRegistry
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.protocol.CoreSignal
import io.github.nicolals.nostr.core.protocol.InboundMessage
import io.github.nicolals.nostr.core.protocol.ProtocolEffect
import io.github.nicolals.nostr.core.protocol.ProtocolInput
import io.github.nicolals.nostr.core.protocol.ProtocolSignal
import io.github.nicolals.nostr.core.protocol.ProtocolState
import io.github.nicolals.nostr.core.protocol.TimerFired
import io.github.nicolals.nostr.core.protocol.TimerKey
import io.github.nicolals.nostr.core.protocol.TransportInput
import io.github.nicolals.nostr.core.runtime.CloseReason
import io.github.nicolals.nostr.core.runtime.ClosePolicy
import io.github.nicolals.nostr.core.runtime.ConnectionStatus
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.core.runtime.ReconnectDecision
import io.github.nicolals.nostr.core.runtime.RelaySession
import io.github.nicolals.nostr.core.runtime.RetryDecision
import io.github.nicolals.nostr.core.runtime.SessionConfig
import io.github.nicolals.nostr.core.runtime.SubscriptionRetryReason
import io.github.nicolals.nostr.core.runtime.SubscriptionStatus
import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.RelayConnection
import io.github.nicolals.nostr.core.transport.RelayConnectionListener
import io.github.nicolals.nostr.core.transport.RelayTransportFactory
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.core.transport.TransportError
import io.github.nicolals.nostr.core.transport.TransportResult
import io.github.nicolals.nostr.core.transport.TransportState
import io.github.nicolals.nostr.nip01.Nip01Module
import io.github.nicolals.nostr.nip01.protocol.Nip01Intent
import io.github.nicolals.nostr.nip01.protocol.Nip01Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.concurrent.Volatile

/**
 * Coroutines-based implementation of [RelaySession].
 *
 * Manages a single relay connection with automatic reconnection, retry of
 * interrupted operations, and event deduplication based on configured policies.
 *
 * ## Threading Model
 *
 * All state mutations happen in a single actor coroutine that processes inputs
 * sequentially. This ensures thread safety without explicit synchronization.
 * Public methods send inputs to the actor via a channel.
 *
 * ## NIP Support
 *
 * The session internally installs NIP-01 for basic protocol support. Additional
 * NIP modules can be passed via [additionalModules] for typed event views.
 *
 * ## Lifecycle
 *
 * ```kotlin
 * val session = CoroutinesRelaySession(
 *     url = RelayUrl.parse("wss://relay.example.com"),
 *     codec = codec,
 *     eventVerifier = verifier,
 *     transportFactory = KtorRelayTransportFactory(httpClient),
 *     scope = viewModelScope,
 * )
 *
 * // Start connection
 * session.connect()
 *
 * // Optionally wait for connection
 * session.awaitConnected(timeout = 5000)
 *
 * // Subscribe to events
 * val handle = session.subscribe(listOf(filter))
 * handle.events.collect { event -> process(event) }
 *
 * // Publish an event
 * val publishHandle = session.publish(signedEvent)
 * val result = publishHandle.await()
 *
 * // Cleanup (suspends until fully closed)
 * session.closeAndJoin()
 * ```
 *
 * ## Typed Event Views
 *
 * To get typed event views, install additional NIP modules and use [events]:
 *
 * ```kotlin
 * val session = CoroutinesRelaySession(
 *     url = relayUrl,
 *     codec = codec,
 *     eventVerifier = verifier,
 *     transportFactory = factory,
 *     scope = scope,
 *     additionalModules = listOf(myNipModule),
 * )
 *
 * subscription.events.collect { event ->
 *     when (val view = session.events.upgrade(event)) {
 *         is Nip47ResponseEvent -> handleResponse(view)
 *         else -> { /* unknown */ }
 *     }
 * }
 * ```
 *
 * @param url The relay URL to connect to.
 * @param codec JSON and Nostr wire encoding.
 * @param eventVerifier Verifies event identity and signature before delivery.
 * @param transportFactory Factory for creating WebSocket connections.
 * @param scope The coroutine scope for the session's coroutines.
 * @param config Session configuration (policies).
 * @param additionalModules Additional NIP modules to install for typed event views.
 */
class CoroutinesRelaySession(
    override val url: RelayUrl,
    private val codec: NostrJsonCodec,
    private val eventVerifier: EventVerifier,
    private val transportFactory: RelayTransportFactory,
    private val scope: CoroutineScope,
    private val config: SessionConfig = SessionConfig.Default,
    additionalModules: List<NipModule> = emptyList(),
) : RelaySession {

    // ========== Internal Registry ==========

    // Build registry with NIP-01 (required) + any additional modules
    private val registry: NipRegistry = NipRegistry.Builder(codec)
        .install(Nip01Module(codec))
        .apply { additionalModules.forEach { install(it) } }
        .build()

    /**
     * Event upgrader for clients that need typed event views.
     *
     * Use this to convert raw [Event] objects to typed views based on
     * installed NIP modules.
     *
     * ```kotlin
     * subscription.events.collect { event ->
     *     val view = session.events.upgrade(event)
     *     // view is now a typed EventView based on the event kind
     * }
     * ```
     */
    val events: EventUpgradeRegistry get() = registry.events

    // TODO: Consider adding subscribeTyped<T : EventView>() that returns
    // a TypedSubscriptionHandle<T> where events are pre-upgraded to T.
    // This would provide a more ergonomic API for clients that only care
    // about specific event types.

    // ========== Public State ==========

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)

    /**
     * Observable connection status as a [StateFlow].
     */
    val connectionStatusFlow: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    override val connectionStatus: ConnectionStatus
        get() = _connectionStatus.value

    /**
     * Whether this session has been closed.
     *
     * Once closed, a session cannot be reused.
     */
    val isClosed: Boolean
        get() = _isClosed

    // ========== Observable Flows ==========

    private val _notices = MutableSharedFlow<RelayNotice>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Flow of NOTICE messages from the relay.
     *
     * These are human-readable messages sent by relays for informational
     * or error reporting purposes. Each notice includes the relay URL.
     */
    val notices: SharedFlow<RelayNotice> = _notices.asSharedFlow()

    private val _errors = MutableSharedFlow<SessionError>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Flow of session errors.
     *
     * These include parsing errors, protocol errors, and other issues
     * that don't cause the session to fail but should be observable.
     */
    val errors: SharedFlow<SessionError> = _errors.asSharedFlow()

    private val signalStream = SessionEventStream<ProtocolSignal>(config.observerBufferCapacity)

    /**
     * Signals from every installed reducer, including third-party NIPs. Start
     * collecting before [submit]. Every active observer receives each signal;
     * a slow observer fails with [SessionObservationOverflowException] if its
     * configured buffer fills. Other observers and session cleanup keep running.
     * There is no replay. The flow completes when the session closes.
     */
    val signals: Flow<ProtocolSignal> = signalStream.events

    private val messageStream = SessionEventStream<RelayMessageView>(config.observerBufferCapacity)

    /**
     * Decoded inbound messages, including messages with no matching reducer.
     * EVENT messages pass [eventVerifier] before observation. Uses the same
     * independent observer buffering contract as [signals].
     */
    val messages: Flow<RelayMessageView> = messageStream.events

    // ========== Actor Infrastructure ==========

    private data class QueuedInput(val input: SessionInput, val bounded: Boolean)

    // User operations and incoming messages are bounded. Lifecycle callbacks and
    // cleanup commands share the ordered mailbox but never compete for admission.
    private val inputSlots = Semaphore(1000)
    private val inputChannel = Channel<QueuedInput>(Channel.UNLIMITED)
    private val closeRequested = MutableStateFlow(false)
    private val connectRequested = MutableStateFlow(false)
    private val overflowedTransportGeneration = MutableStateFlow(0L)
    private var actorJob: Job? = null

    // ========== Mutable State (only accessed from actor) ==========

    private var protocolState = ProtocolState()
    private var transport: RelayConnection? = null
    private var transportGeneration = 0L
    private var transportClosed: CompletableDeferred<Unit>? = null
    private val retiringTransports = mutableMapOf<Long, Pair<RelayConnection, CompletableDeferred<Unit>>>()
    private var reconnectAttempt = 0
    private var intentionalDisconnect = false
    private var connectAfterDisconnect = false
    @Volatile
    private var _isClosed = false

    // Handle tracking
    private val subscriptions = mutableMapOf<SubscriptionId, SubscriptionRecord>()
    private val publishes = mutableMapOf<EventId, PublishRecord>()

    // Timer management
    private val activeTimers = mutableMapOf<TimerKey, Job>()

    // Ping loop job for keepalive
    private var pingJob: Job? = null

    // Track pending jobs (reconnect delays, retry delays) so we can cancel on close
    private val pendingJobs = mutableSetOf<Job>()

    // Close timeout tracking for client-initiated closes
    private val closeTimeoutJobs = mutableMapOf<SubscriptionId, Job>()

    // Close completion signal
    private val closeCompletion = CompletableDeferred<Unit>()

    // JSON codec for parsing messages
    private val jsonCodec: JsonCodec
        get() = codec

    // ========== Initialization ==========

    init {
        startActor()
    }

    private fun startActor() {
        // Enter the try/finally even when the supplied scope is already cancelled.
        actorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (queued in inputChannel) {
                    if (queued.bounded) inputSlots.release()
                    val input = queued.input
                    if (input is SessionInput.Connect) connectRequested.value = false
                    if (_isClosed) {
                        rejectInput(input, "Session closed")
                        continue
                    }
                    try {
                        processInput(input)
                    } catch (e: CancellationException) {
                        rejectInput(input, "Session cancelled")
                        throw e
                    } catch (e: Exception) {
                        rejectInput(input, "Protocol processing failed")
                        _errors.tryEmit(SessionError.ProcessingError(url))
                    }
                }
            } finally {
                _isClosed = true
                inputChannel.close()
                while (true) {
                    val queued = inputChannel.tryReceive().getOrNull() ?: break
                    if (queued.bounded) inputSlots.release()
                    rejectInput(queued.input, "Session closed")
                }
                try {
                    withContext(NonCancellable) { cleanupSession() }
                } finally {
                    closeCompletion.complete(Unit)
                }
            }
        }
    }

    private fun enqueueInput(input: SessionInput) {
        if (_isClosed || (closeRequested.value && input !is SessionInput.Close)) {
            rejectInput(input, "Session closed")
            return
        }
        if (input is SessionInput.TransportMessage && input.generation <= overflowedTransportGeneration.value) return
        val bounded = input is SessionInput.Subscribe || input is SessionInput.Publish ||
            input is SessionInput.TransportMessage || input is SessionInput.Submit || input is SessionInput.Send
        if (bounded && !inputSlots.tryAcquire()) {
            rejectInput(input, "Session overloaded")
            _errors.tryEmit(SessionError.InputDropped(url, input::class.simpleName ?: "unknown"))
            if (input is SessionInput.TransportMessage) {
                // Losing a protocol response corrupts the stream. Stop accepting
                // this generation and force retry/failure instead of stranding it.
                while (true) {
                    val failedGeneration = overflowedTransportGeneration.value
                    if (input.generation <= failedGeneration) break
                    if (overflowedTransportGeneration.compareAndSet(failedGeneration, input.generation)) {
                        enqueueInput(SessionInput.TransportFailed(input.generation, TransportError.Backpressure("receive")))
                        break
                    }
                }
            }
            return
        }
        if (inputChannel.trySend(QueuedInput(input, bounded)).isFailure) {
            if (bounded) inputSlots.release()
            rejectInput(input, "Session closed")
        }
    }

    private fun rejectInput(input: SessionInput, reason: String) {
        when (input) {
            is SessionInput.Subscribe -> input.handle.updateStatus(SubscriptionStatus.Closed(reason))
            is SessionInput.Publish -> input.handle.updateStatus(PublishStatus.Abandoned)
            is SessionInput.Submit -> input.completion.completeExceptionally(IllegalStateException(reason))
            is SessionInput.Send -> input.completion.completeExceptionally(IllegalStateException(reason))
            else -> Unit
        }
    }

    private fun trackPendingJob(job: Job) {
        pendingJobs.add(job)
        job.invokeOnCompletion {
            enqueueInput(SessionInput.PendingJobCompleted(job))
        }
    }

    // ========== Public API ==========

    override fun connect() {
        if (_isClosed) return
        if (connectRequested.compareAndSet(false, true)) enqueueInput(SessionInput.Connect)
    }

    override fun disconnect(code: Int, reason: String?) {
        if (_isClosed) return
        enqueueInput(SessionInput.Disconnect(code, reason))
    }

    override fun close() {
        if (closeRequested.compareAndSet(false, true)) enqueueInput(SessionInput.Close)
    }

    /**
     * Closes the session and suspends until cleanup is complete.
     *
     * After this returns, all resources are released and the session
     * cannot be reused.
     */
    suspend fun closeAndJoin() {
        close()
        closeCompletion.await()
    }

    /**
     * Suspends until the connection is established or timeout occurs.
     *
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @return True if connected, false if timeout occurred.
     */
    suspend fun awaitConnected(timeoutMs: Long = 10_000): Boolean {
        if (_isClosed) return false

        return withTimeoutOrNull(timeoutMs) {
            connectionStatusFlow.first { it == ConnectionStatus.Connected }
            true
        } ?: false
    }

    /**
     * Subscribes to events matching the given filters.
     *
     * @param filters The filters defining which events to receive.
     * @param subscriptionId Optional subscription ID. If not provided, a random ID is generated.
     * @return A handle for tracking the subscription and receiving events.
     */
    fun subscribe(
        filters: List<Filter>,
        subscriptionId: SubscriptionId = generateSubscriptionId(),
    ): CoroutinesSubscriptionHandle {
        val handle = CoroutinesSubscriptionHandle(
            subscriptionId = subscriptionId,
            closeCallback = { handle -> enqueueInput(SessionInput.CloseSubscription(handle)) },
            onBackpressure = { id, eventId, droppedCount ->
                _errors.tryEmit(SessionError.BackpressureDropped(url, id, eventId, droppedCount))
            },
        )

        if (_isClosed) {
            handle.updateStatus(SubscriptionStatus.Closed())
            return handle
        }

        enqueueInput(SessionInput.Subscribe(subscriptionId, filters, handle))
        return handle
    }

    /**
     * Publishes an event to the relay.
     *
     * @param event The signed event to publish.
     * @return A handle for tracking the publish status.
     */
    fun publish(event: Event): CoroutinesPublishHandle {
        val handle = CoroutinesPublishHandle(event.id)

        if (_isClosed) {
            handle.updateStatus(PublishStatus.Abandoned)
            return handle
        }

        enqueueInput(SessionInput.Publish(event, handle))
        return handle
    }

    /**
     * Submits an intent to the installed protocol reducers and waits for it to be
     * processed. NIP modules can expose their own [ProtocolInput] types without
     * requiring runtime changes. Throws if the session is closed or overloaded.
     */
    suspend fun submit(input: ProtocolInput) {
        val completion = CompletableDeferred<Unit>()
        enqueueInput(SessionInput.Submit(input, completion))
        completion.await()
    }

    /**
     * Encodes and queues a custom client message on the current transport.
     * Success means transport admission, not relay acknowledgement. Use [submit]
     * when a module also needs to maintain protocol state or produce signals.
     * Throws if the session is closed or overloaded.
     */
    suspend fun send(message: ClientMessageView): TransportResult<Unit> {
        val completion = CompletableDeferred<TransportResult<Unit>>()
        enqueueInput(SessionInput.Send(message, completion))
        return completion.await()
    }

    // ========== Actor Input Processing ==========

    private fun processInput(input: SessionInput) {
        when (input) {
            // Transport events
            is SessionInput.TransportOpened -> if (isCurrentTransport(input.generation)) handleTransportOpened()
            is SessionInput.TransportMessage -> if (isCurrentTransport(input.generation)) handleTransportMessage(input.text)
            is SessionInput.TransportClosing -> { /* Ignore, wait for Closed */
            }

            is SessionInput.TransportClosed -> {
                retiringTransports.remove(input.generation)
                if (isCurrentTransport(input.generation)) handleTransportClosed(input.info)
            }
            is SessionInput.TransportFailed -> if (isCurrentTransport(input.generation)) handleTransportFailed(input.error)

            // User commands
            is SessionInput.Connect -> handleConnect()
            is SessionInput.Disconnect -> handleDisconnect(input.code, input.reason)
            is SessionInput.Close -> handleClose()
            is SessionInput.Subscribe -> handleSubscribe(input)
            is SessionInput.Publish -> handlePublish(input)
            is SessionInput.CloseSubscription -> handleCloseSubscription(input.handle)
            is SessionInput.Submit -> {
                driveEngine(input.input)
                input.completion.complete(Unit)
            }
            is SessionInput.Send -> input.completion.complete(sendMessage(input.message))
            is SessionInput.Ping -> if (isCurrentTransport(input.generation)) {
                val result = transport?.ping(null)
                if (result is TransportResult.Failure) handleTransportFailed(result.error)
            }

            // Internal events
            is SessionInput.TimerFired -> handleTimerFired(input.key)
            is SessionInput.CloseTimeout -> handleCloseTimeout(input.subscriptionId)
            is SessionInput.ReconnectNow -> if (input.generation == transportGeneration) handleReconnectNow()
            is SessionInput.RetryPublish -> handleRetryPublish(input.eventId, input.handle)
            is SessionInput.RetrySubscription -> handleRetrySubscription(input.subscriptionId, input.handle)
            is SessionInput.PendingJobCompleted -> pendingJobs.remove(input.job)
        }
    }

    // ========== Transport Lifecycle ==========

    private fun isCurrentTransport(generation: Long): Boolean =
        transport != null && generation == transportGeneration

    private fun handleConnect() {
        if (_isClosed) return
        if (intentionalDisconnect && transport != null) {
            connectAfterDisconnect = true
            return
        }
        if (_connectionStatus.value == ConnectionStatus.Connected ||
            _connectionStatus.value == ConnectionStatus.Connecting
        ) {
            return // Already connected or connecting
        }

        intentionalDisconnect = false
        reconnectAttempt = 0
        startConnection()
    }

    private fun startConnection() {
        _connectionStatus.value = ConnectionStatus.Connecting

        val generation = ++transportGeneration
        val connection = transportFactory.create(url)
        val closed = CompletableDeferred<Unit>()
        transport = connection
        transportClosed = closed

        connection.setListener(object : RelayConnectionListener {
            override fun onOpen() {
                enqueueInput(SessionInput.TransportOpened(generation))
            }

            override fun onMessage(text: String) {
                enqueueInput(SessionInput.TransportMessage(generation, text))
            }

            override fun onPong(payload: okio.ByteString?) {
                // Could track for keepalive, but not critical
            }

            override fun onClosing(info: CloseInfo) {
                enqueueInput(SessionInput.TransportClosing(generation, info))
            }

            override fun onClosed(info: CloseInfo) {
                closed.complete(Unit)
                connection.setListener(null)
                enqueueInput(SessionInput.TransportClosed(generation, info))
            }

            override fun onFailure(error: TransportError) {
                enqueueInput(SessionInput.TransportFailed(generation, error))
            }
        })

        try {
            connection.connect()
        } catch (e: Exception) {
            handleTransportFailed(TransportError.IoError("Failed to connect", e))
        }
    }

    private fun handleTransportOpened() {
        _connectionStatus.value = ConnectionStatus.Connected
        reconnectAttempt = 0

        // Start ping loop if configured
        startPingLoop()

        // Drive engine with TransportOpened
        driveEngine(TransportInput.Opened)

        // Send all pending subscriptions (Starting) or retry interrupted ones
        for ((_, record) in subscriptions.toMap()) {
            if (_connectionStatus.value != ConnectionStatus.Connected) break
            when (val status = record.handle.status) {
                SubscriptionStatus.Starting -> sendSubscribe(record)
                SubscriptionStatus.Interrupted -> retrySubscription(record, SubscriptionRetryReason.Disconnected)
                is SubscriptionStatus.Retrying -> retrySubscription(
                    record,
                    record.lastRetryReason ?: SubscriptionRetryReason.Disconnected,
                )
                else -> {}
            }
        }

        // Send all pending publishes (Queued) or retry interrupted ones
        for ((_, record) in publishes.toMap()) {
            if (_connectionStatus.value != ConnectionStatus.Connected) break
            when (record.handle.status) {
                PublishStatus.Queued -> sendPublish(record)
                PublishStatus.Interrupted -> retryPublish(record)
                is PublishStatus.Retrying -> retryPublish(record)
                else -> {}
            }
        }
    }

    private fun handleTransportClosed(info: CloseInfo) {
        val currentTransport = transport
        transport = null
        currentTransport?.setListener(null)
        currentTransport?.close()
        transportClosed = null

        // Stop ping loop
        stopPingLoop()

        // Drive engine with transport closed
        driveEngine(TransportInput.Closed(info))

        // Cancel all active timers
        cancelAllTimers()

        // Finalize any subscriptions that were closing
        finalizeClosingSubscriptions("Connection closed while closing")

        // Handle reconnection
        if (connectAfterDisconnect && !_isClosed) {
            restartAfterDisconnect()
        } else if (!intentionalDisconnect && !_isClosed) {
            attemptReconnect(null)
        } else {
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun handleTransportFailed(error: TransportError) {
        val currentTransport = transport
        val closed = transportClosed
        transport = null
        transportClosed = null
        if (currentTransport != null && closed != null) {
            retiringTransports[transportGeneration] = currentTransport to closed
            currentTransport.close()
        }

        // Stop ping loop
        stopPingLoop()

        // Drive engine with transport failed
        driveEngine(TransportInput.Failed(error))

        // Cancel all active timers
        cancelAllTimers()

        // Finalize any subscriptions that were closing
        finalizeClosingSubscriptions("Connection failed while closing")

        // Handle reconnection
        if (connectAfterDisconnect && !_isClosed) {
            restartAfterDisconnect()
        } else if (!intentionalDisconnect && !_isClosed) {
            attemptReconnect(error)
        } else {
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun handleDisconnect(code: Int, reason: String?) {
        intentionalDisconnect = true
        connectAfterDisconnect = false
        if (transport != null) {
            transport?.close(code, reason)
        } else {
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun restartAfterDisconnect() {
        connectAfterDisconnect = false
        intentionalDisconnect = false
        reconnectAttempt = 0
        startConnection()
    }

    private fun handleClose() {
        _isClosed = true
        inputChannel.close()
    }

    private suspend fun cleanupSession() {
        intentionalDisconnect = true

        // Stop ping loop
        stopPingLoop()

        // Cancel all pending jobs (reconnect delays, retry delays)
        for (job in pendingJobs) {
            job.cancel()
        }
        pendingJobs.clear()

        // Cancel all close timeouts
        for ((_, job) in closeTimeoutJobs) {
            job.cancel()
        }
        closeTimeoutJobs.clear()

        // Cancel all timers
        cancelAllTimers()

        // Close all subscription handles
        for ((_, record) in subscriptions) {
            record.deduplication.clear()
            if (!record.handle.status.isTerminal) {
                record.handle.updateStatus(SubscriptionStatus.Closed())
            }
        }
        subscriptions.clear()

        // Update all publish handles
        for ((_, record) in publishes) {
            if (!record.handle.status.isTerminal) {
                record.handle.updateStatus(PublishStatus.Abandoned)
            }
        }
        publishes.clear()

        // Update connection status
        _connectionStatus.value = ConnectionStatus.Disconnected
        signalStream.close()
        messageStream.close()

        val closing = retiringTransports.values.toMutableList()
        val connection = transport
        val closed = transportClosed
        if (connection != null && closed != null) closing.add(connection to closed)
        transport = null
        transportClosed = null
        retiringTransports.clear()
        try {
            for ((candidate, _) in closing) {
                try {
                    candidate.close()
                } catch (_: Exception) {
                    _errors.tryEmit(SessionError.ProcessingError(url))
                }
            }
            withTimeoutOrNull(3_000) {
                closing.forEach { (_, completion) -> completion.await() }
            }
        } finally {
            closing.forEach { (candidate, _) -> candidate.setListener(null) }
        }

    }

    // ========== Reconnection Logic ==========

    private fun attemptReconnect(error: TransportError?) {
        reconnectAttempt++

        val decision = config.reconnectPolicy.shouldReconnect(reconnectAttempt, error)

        when (decision) {
            is ReconnectDecision.Reconnect -> {
                _connectionStatus.value = ConnectionStatus.Reconnecting(
                    attempt = reconnectAttempt,
                    delayMs = decision.delayMs,
                    lastError = error,
                )

                // A manual connect invalidates this delayed attempt.
                val generation = transportGeneration
                val job = scope.launch {
                    delay(decision.delayMs)
                    if (!_isClosed) {
                        enqueueInput(SessionInput.ReconnectNow(generation))
                    }
                }
                trackPendingJob(job)
            }

            is ReconnectDecision.GiveUp -> {
                _connectionStatus.value = ConnectionStatus.Failed(error, reconnectAttempt)

                // Mark all pending operations as abandoned
                for ((_, record) in publishes.toMap()) {
                    if (!record.handle.status.isTerminal) {
                        record.handle.updateStatus(PublishStatus.Abandoned)
                    }
                    forgetPublish(record.eventId)
                }
                publishes.clear()

                // Mark subscriptions as closed
                for ((_, record) in subscriptions.toMap()) {
                    if (!record.handle.status.isTerminal) {
                        record.handle.updateStatus(SubscriptionStatus.Closed("Connection failed"))
                    }
                    forgetSubscription(record.subscriptionId)
                }
                subscriptions.clear()
            }
        }
    }

    private fun handleReconnectNow() {
        if (_isClosed || intentionalDisconnect) return
        if (transport != null) return

        startConnection()
    }

    // ========== Subscription Handling ==========

    private fun handleSubscribe(input: SessionInput.Subscribe) {
        if (_isClosed) {
            input.handle.updateStatus(SubscriptionStatus.Closed())
            return
        }

        // Check for existing subscription with same ID
        val existing = subscriptions[input.subscriptionId]
        if (existing != null) {
            // Replace the subscription - close the old handle
            existing.handle.updateStatus(SubscriptionStatus.Closed("Replaced by new subscription"))
            existing.deduplication.clear()
            cancelCloseTimeout(input.subscriptionId)
        }

        // Store the subscription record
        val record = SubscriptionRecord(
            subscriptionId = input.subscriptionId,
            filters = input.filters,
            handle = input.handle,
            deduplication = config.deduplicationPolicyFactory(),
        )
        subscriptions[input.subscriptionId] = record

        // If connected, send REQ immediately
        if (_connectionStatus.value == ConnectionStatus.Connected) {
            sendSubscribe(record)
        } else {
            // Will be sent when connected
            input.handle.updateStatus(SubscriptionStatus.Starting)
        }
    }

    private fun sendSubscribe(record: SubscriptionRecord) {
        record.handle.updateStatus(SubscriptionStatus.CatchingUp)
        driveEngine(Nip01Intent.Subscribe(record.subscriptionId, record.filters))
    }

    private fun handleCloseSubscription(handle: CoroutinesSubscriptionHandle) {
        val subscriptionId = handle.subscriptionId
        val record = subscriptions[subscriptionId] ?: return
        if (record.handle !== handle) return
        if (record.handle.status.isTerminal) return

        if (_connectionStatus.value == ConnectionStatus.Connected) {
            driveEngine(Nip01Intent.CloseSubscription(subscriptionId))
            when (val policy = config.closePolicy) {
                ClosePolicy.Immediate -> {
                    record.handle.updateStatus(SubscriptionStatus.Closed(byRelay = false))
                    removeSubscription(subscriptionId)
                    cancelCloseTimeout(subscriptionId)
                    forgetSubscription(subscriptionId)
                }

                is ClosePolicy.Timeout -> {
                    record.handle.updateStatus(SubscriptionStatus.Closing)
                    scheduleCloseTimeout(subscriptionId, policy.timeoutMs)
                }

                ClosePolicy.WaitForever -> {
                    record.handle.updateStatus(SubscriptionStatus.Closing)
                    cancelCloseTimeout(subscriptionId)
                }
            }
        } else {
            record.handle.updateStatus(SubscriptionStatus.Closed(byRelay = false))
            removeSubscription(subscriptionId)
            cancelCloseTimeout(subscriptionId)
            forgetSubscription(subscriptionId)
        }
    }

    private fun finalizeClosingSubscriptions(reason: String?) {
        val closingIds = subscriptions.values
            .filter { it.handle.status == SubscriptionStatus.Closing }
            .map { it.subscriptionId }

        for (subscriptionId in closingIds) {
            val record = removeSubscription(subscriptionId) ?: continue
            cancelCloseTimeout(subscriptionId)
            record.handle.updateStatus(SubscriptionStatus.Closed(reason, byRelay = false))
            forgetSubscription(subscriptionId)
        }
    }

    private fun scheduleCloseTimeout(subscriptionId: SubscriptionId, delayMs: Long) {
        cancelCloseTimeout(subscriptionId)

        if (delayMs <= 0) {
            enqueueInput(SessionInput.CloseTimeout(subscriptionId))
            return
        }

        val job = scope.launch {
            delay(delayMs)
            if (!_isClosed) {
                enqueueInput(SessionInput.CloseTimeout(subscriptionId))
            }
        }
        closeTimeoutJobs[subscriptionId] = job
    }

    private fun cancelCloseTimeout(subscriptionId: SubscriptionId) {
        closeTimeoutJobs.remove(subscriptionId)?.cancel()
    }

    private fun handleCloseTimeout(subscriptionId: SubscriptionId) {
        val record = subscriptions[subscriptionId] ?: return
        if (record.handle.status != SubscriptionStatus.Closing) return

        cancelCloseTimeout(subscriptionId)
        record.handle.updateStatus(SubscriptionStatus.Closed("Close timeout", byRelay = false))
        removeSubscription(subscriptionId)
        forgetSubscription(subscriptionId)
    }

    private fun retrySubscription(record: SubscriptionRecord, reason: SubscriptionRetryReason) {
        val attempt = record.retryCount + 1
        when (val decision = config.retryPolicy.shouldResubscribe(record.subscriptionId, attempt, reason)) {
            is RetryDecision.Retry -> {
                val updatedRecord = record.copy(
                    retryCount = attempt,
                    lastRetryReason = reason,
                )
                subscriptions[record.subscriptionId] = updatedRecord

                if (decision.delayMs > 0) {
                    updatedRecord.handle.updateStatus(
                        SubscriptionStatus.Retrying(reason, attempt, decision.delayMs)
                    )
                    val job = scope.launch {
                        delay(decision.delayMs)
                        if (!_isClosed) {
                            enqueueInput(SessionInput.RetrySubscription(record.subscriptionId, record.handle))
                        }
                    }
                    trackPendingJob(job)
                } else {
                    sendSubscribe(updatedRecord)
                }
            }

            is RetryDecision.Abandon -> {
                val reasonMessage = when (reason) {
                    SubscriptionRetryReason.Disconnected -> "Retry policy declined"
                    is SubscriptionRetryReason.Closed -> reason.reason
                }
                record.handle.updateStatus(
                    SubscriptionStatus.Closed(reasonMessage, byRelay = reason is SubscriptionRetryReason.Closed)
                )
                removeSubscription(record.subscriptionId)
                forgetSubscription(record.subscriptionId)
            }
        }
    }

    private fun handleRetrySubscription(subscriptionId: SubscriptionId, handle: CoroutinesSubscriptionHandle) {
        val record = subscriptions[subscriptionId] ?: return
        if (record.handle !== handle) return
        if (_connectionStatus.value != ConnectionStatus.Connected) return
        val status = record.handle.status
        if (status != SubscriptionStatus.Interrupted && status !is SubscriptionStatus.Retrying) return
        sendSubscribe(record)
    }

    // ========== Publish Handling ==========

    private fun handlePublish(input: SessionInput.Publish) {
        if (_isClosed) {
            input.handle.updateStatus(PublishStatus.Abandoned)
            return
        }

        val existing = publishes[input.event.id]
        if (existing != null && !existing.handle.status.isTerminal) {
            existing.handle.updateStatus(PublishStatus.Replaced("Replaced by new publish"))
        }

        // Store the publish record
        val record = PublishRecord(
            eventId = input.event.id,
            event = input.event,
            handle = input.handle,
        )
        publishes[input.event.id] = record

        // If connected, send EVENT immediately
        if (_connectionStatus.value == ConnectionStatus.Connected) {
            sendPublish(record)
        } else {
            // Will be sent when connected
            input.handle.updateStatus(PublishStatus.Queued)
        }
    }

    private fun sendPublish(record: PublishRecord) {
        record.handle.updateStatus(PublishStatus.Pending)
        driveEngine(Nip01Intent.Publish(record.event))
    }

    private fun retryPublish(record: PublishRecord) {
        when (val decision = config.retryPolicy.shouldRetryPublish(record.eventId, record.retryCount + 1)) {
            is RetryDecision.Retry -> {
                val attempt = record.retryCount + 1
                val updatedRecord = record.copy(retryCount = attempt)
                publishes[record.eventId] = updatedRecord

                if (decision.delayMs > 0) {
                    updatedRecord.handle.updateStatus(
                        PublishStatus.Retrying(attempt, decision.delayMs)
                    )
                    // Schedule delayed retry with tracked job
                    val job = scope.launch {
                        delay(decision.delayMs)
                        if (!_isClosed) {
                            enqueueInput(SessionInput.RetryPublish(record.eventId, record.handle))
                        }
                    }
                    trackPendingJob(job)
                } else {
                    sendPublish(updatedRecord)
                }
            }

            is RetryDecision.Abandon -> {
                record.handle.updateStatus(PublishStatus.Abandoned)
                publishes.remove(record.eventId)
                forgetPublish(record.eventId)
            }
        }
    }

    private fun handleRetryPublish(eventId: EventId, handle: CoroutinesPublishHandle) {
        val record = publishes[eventId] ?: return
        if (record.handle !== handle) return
        if (_connectionStatus.value != ConnectionStatus.Connected) return
        val status = record.handle.status
        if (status != PublishStatus.Interrupted && status !is PublishStatus.Retrying) return
        sendPublish(record)
    }

    private fun removeSubscription(subscriptionId: SubscriptionId): SubscriptionRecord? =
        subscriptions.remove(subscriptionId)?.also { it.deduplication.clear() }

    private fun forgetSubscription(subscriptionId: SubscriptionId) {
        driveEngine(Nip01Intent.ForgetSubscription(subscriptionId))
    }

    private fun forgetPublish(eventId: EventId) {
        driveEngine(Nip01Intent.ForgetPublish(eventId))
    }

    // ========== Engine Integration ==========

    private fun driveEngine(input: ProtocolInput) {
        val result = registry.engine.process(protocolState, input)
        protocolState = result.state

        // Execute effects
        for (effect in result.effects) {
            executeEffect(effect)
        }

        // Process signals
        for (signal in result.signals) {
            processSignal(signal)
        }
    }

    private fun executeEffect(effect: ProtocolEffect) {
        when (effect) {
            is ProtocolEffect.Send -> {
                when (val result = sendMessage(effect.message)) {
                    is TransportResult.Success -> Unit
                    is TransportResult.Failure -> {
                        _errors.tryEmit(SessionError.SendFailed(url, result.error))
                        // Process after this reducer's signals so a queued signal
                        // cannot overwrite the subsequent interrupted status.
                        enqueueInput(SessionInput.TransportFailed(transportGeneration, result.error))
                    }
                }
            }

            is ProtocolEffect.ScheduleTimer -> {
                scheduleTimer(effect.key, effect.delayMs)
            }

            is ProtocolEffect.CancelTimer -> {
                cancelTimer(effect.key)
            }
        }
    }

    private fun sendMessage(message: ClientMessageView): TransportResult<Unit> {
        val connection = transport ?: return TransportResult.failure(
            TransportError.InvalidState("send", TransportState.Closed)
        )
        val json = registry.wireCodec.encodeClientMessage(message)
        return connection.send(jsonCodec.stringify(json))
    }

    private fun handleTransportMessage(text: String) {
        try {
            val jsonValue = jsonCodec.parse(text)
            val rawMessage = registry.wireCodec.decodeRelayMessage(jsonValue)
            val messageView = registry.messages.upgradeRelay(rawMessage)
            if (messageView is RelayEventMessage) {
                val event = messageView.event ?: return
                when (val verification = eventVerifier.verify(event)) {
                    is CryptoResult.Ok -> Unit
                    is CryptoResult.Err -> {
                        _errors.tryEmit(SessionError.EventRejected(url, event.id))
                        return
                    }
                }
            }
            messageStream.emit(messageView)
            driveEngine(InboundMessage(messageView))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Malformed message - emit error and continue
            _errors.tryEmit(SessionError.ParseError(url))
        }
    }

    // ========== Signal Processing ==========

    private fun processSignal(signal: ProtocolSignal) {
        signalStream.emit(signal)
        when (signal) {
            is CoreSignal -> processCoreSignal(signal)
            is Nip01Signal -> processNip01Signal(signal)
            else -> {
                // Third-party signals are exposed through signals above.
            }
        }
    }

    private fun processCoreSignal(signal: CoreSignal) {
        // Core signals are handled by transport lifecycle methods
        // Nothing additional to do here
    }

    private fun processNip01Signal(signal: Nip01Signal) {
        when (signal) {
            is Nip01Signal.SubscriptionStarted -> {
                val record = subscriptions[signal.subscriptionId]
                if (record != null) {
                    val updatedRecord = record.copy(
                        generation = signal.generation,
                    )
                    subscriptions[signal.subscriptionId] = updatedRecord
                    record.handle.updateStatus(SubscriptionStatus.CatchingUp)
                }
            }

            is Nip01Signal.SubscriptionEose -> {
                val record = subscriptions[signal.subscriptionId]
                if (record != null && record.generation == signal.generation) {
                    subscriptions[signal.subscriptionId] = record.copy(retryCount = 0, lastRetryReason = null)
                    record.handle.updateStatus(SubscriptionStatus.Live)
                }
            }

            is Nip01Signal.SubscriptionEvent -> {
                val record = subscriptions[signal.subscriptionId]
                if (record != null && record.generation == signal.generation) {
                    // Check deduplication
                    if (!record.deduplication.isDuplicate(signal.event.id)) {
                        val sent = record.handle.sendEvent(signal.event)
                        if (!sent) {
                            // Event was dropped due to backpressure
                            // Error already emitted by handle callback
                        }
                    }
                }
            }

            is Nip01Signal.SubscriptionClosed -> {
                val record = subscriptions[signal.subscriptionId]
                if (record != null &&
                    record.generation == signal.generation &&
                    !record.handle.status.isTerminal
                ) {
                    cancelCloseTimeout(signal.subscriptionId)
                    if (signal.byRelay) {
                        val closeReason = parseCloseReason(signal.reason)
                        retrySubscription(
                            record,
                            SubscriptionRetryReason.Closed(signal.reason, closeReason),
                        )
                    } else {
                        record.handle.updateStatus(SubscriptionStatus.Closed(signal.reason, false))
                        removeSubscription(signal.subscriptionId)
                        forgetSubscription(signal.subscriptionId)
                    }
                }
            }

            is Nip01Signal.SubscriptionInterrupted -> {
                val record = subscriptions[signal.subscriptionId]
                if (record != null &&
                    record.generation == signal.generation &&
                    !record.handle.status.isTerminal
                ) {
                    subscriptions[signal.subscriptionId] = record.copy(
                        lastRetryReason = SubscriptionRetryReason.Disconnected,
                    )
                    record.handle.updateStatus(SubscriptionStatus.Interrupted)
                }
            }

            is Nip01Signal.PublishQueued -> {
                val record = publishes[signal.eventId]
                if (record != null && !record.handle.status.isTerminal) {
                    record.handle.updateStatus(PublishStatus.Pending)
                }
            }

            is Nip01Signal.PublishAccepted -> {
                val record = publishes.remove(signal.eventId)
                record?.handle?.updateStatus(PublishStatus.Accepted(signal.message))
                if (record != null) {
                    forgetPublish(record.eventId)
                }
            }

            is Nip01Signal.PublishRejected -> {
                val record = publishes.remove(signal.eventId)
                record?.handle?.updateStatus(PublishStatus.Rejected(signal.message))
                if (record != null) {
                    forgetPublish(record.eventId)
                }
            }

            is Nip01Signal.PublishInterrupted -> {
                val record = publishes[signal.eventId]
                if (record != null && !record.handle.status.isTerminal) {
                    record.handle.updateStatus(PublishStatus.Interrupted)
                }
            }

            is Nip01Signal.Notice -> {
                _notices.tryEmit(RelayNotice(url, signal.message))
            }
        }
    }

    // ========== Timer Management ==========

    private fun scheduleTimer(key: TimerKey, delayMs: Long) {
        // Cancel existing timer with same key
        cancelTimer(key)

        val job = scope.launch {
            delay(delayMs)
            if (!_isClosed) {
                enqueueInput(SessionInput.TimerFired(key))
            }
        }
        activeTimers[key] = job
    }

    private fun cancelTimer(key: TimerKey) {
        activeTimers.remove(key)?.cancel()
    }

    private fun cancelAllTimers() {
        for ((_, job) in activeTimers) {
            job.cancel()
        }
        activeTimers.clear()
    }

    private fun handleTimerFired(key: TimerKey) {
        activeTimers.remove(key)
        driveEngine(TimerFired(key))
    }

    // ========== Ping Loop ==========

    private fun startPingLoop() {
        stopPingLoop() // Cancel any existing ping loop

        val interval = config.pingIntervalMillis ?: return

        val generation = transportGeneration
        pingJob = scope.launch {
            while (true) {
                delay(interval)
                enqueueInput(SessionInput.Ping(generation))
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    // ========== Utilities ==========

    private fun generateSubscriptionId(): SubscriptionId {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val id = (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return SubscriptionId(id)
    }

    private fun parseCloseReason(message: String?): CloseReason? = message?.let(CloseReason::parse)

}

/**
 * Notice message emitted by the relay, tagged with relay URL.
 */
data class RelayNotice(
    val relayUrl: RelayUrl,
    val message: String,
)

/**
 * Errors that can occur during session operation.
 *
 * These are non-fatal errors that are emitted via [CoroutinesRelaySession.errors].
 */
sealed class SessionError {
    abstract val relayUrl: RelayUrl

    /**
     * Event identity and signature validation failed. No untrusted event content
     * or provider exception is retained in diagnostics.
     */
    data class EventRejected(
        override val relayUrl: RelayUrl,
        val eventId: EventId,
    ) : SessionError()

    /** An inbound message could not be decoded; untrusted contents are omitted. */
    data class ParseError(
        override val relayUrl: RelayUrl,
    ) : SessionError()

    /**
     * An error occurred while processing an input.
     *
     * Parser, codec and reducer exceptions are omitted because their messages
     * may embed private event content.
     */
    data class ProcessingError(
        override val relayUrl: RelayUrl,
    ) : SessionError()

    /**
     * An event was dropped due to backpressure.
     *
     * This occurs when the consumer is slower than events are arriving
     * and the event buffer is full.
     *
     * @property subscriptionId The subscription that dropped the event.
     * @property eventId The event ID that was dropped.
     * @property droppedCount Number of events dropped so far for this subscription.
     */
    data class BackpressureDropped(
        override val relayUrl: RelayUrl,
        val subscriptionId: SubscriptionId,
        val eventId: EventId,
        val droppedCount: Long,
    ) : SessionError()

    /**
     * A send attempt failed at the transport layer.
     *
     * @property error The transport error that occurred.
     */
    data class SendFailed(
        override val relayUrl: RelayUrl,
        val error: TransportError,
    ) : SessionError()

    /**
     * An input could not be enqueued because the session was overloaded.
     *
     * @property inputType A string identifying the input type that was dropped.
     */
    data class InputDropped(
        override val relayUrl: RelayUrl,
        val inputType: String,
    ) : SessionError()
}
