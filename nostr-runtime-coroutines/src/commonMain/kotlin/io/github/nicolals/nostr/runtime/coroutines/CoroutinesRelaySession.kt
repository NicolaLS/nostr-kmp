package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.upgrade.EventUpgradeRegistry
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipModuleContext
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
import io.github.nicolals.nostr.core.runtime.DeduplicationPolicy
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
import io.github.nicolals.nostr.nip42.authRequiredReason
import io.github.nicolals.nostr.nip42.isAuthRequired
import io.github.nicolals.nostr.nip42.isRestricted
import io.github.nicolals.nostr.nip42.restrictedReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
 *     moduleContext = SimpleNipModuleContext(codec, crypto),
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
 *     moduleContext = ctx,
 *     transportFactory = factory,
 *     scope = scope,
 *     additionalModules = listOf(Nip47Module(ctx)),
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
 * @param moduleContext The module context providing codec and crypto primitives.
 * @param transportFactory Factory for creating WebSocket connections.
 * @param scope The coroutine scope for the session's coroutines.
 * @param config Session configuration (policies).
 * @param additionalModules Additional NIP modules to install for typed event views.
 */
class CoroutinesRelaySession(
    override val url: RelayUrl,
    private val moduleContext: NipModuleContext,
    private val transportFactory: RelayTransportFactory,
    private val scope: CoroutineScope,
    private val config: SessionConfig = SessionConfig.Default,
    additionalModules: List<NipModule> = emptyList(),
) : RelaySession {

    // ========== Internal Registry ==========

    // Build registry with NIP-01 (required) + any additional modules
    private val registry: NipRegistry = NipRegistry.Builder(moduleContext.wireCodec)
        .install(Nip01Module(moduleContext))
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

    // ========== Actor Infrastructure ==========

    // Bounded channel to prevent OOM from message floods
    private val inputChannel = Channel<SessionInput>(capacity = 1000)
    private var actorJob: Job? = null

    // ========== Mutable State (only accessed from actor) ==========

    private var protocolState = ProtocolState()
    private var transport: RelayConnection? = null
    private var reconnectAttempt = 0
    private var intentionalDisconnect = false
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

    // Deduplication (accessed from actor only)
    private val deduplication: DeduplicationPolicy = config.deduplicationPolicy

    // Close completion signal
    private val closeCompletion = CompletableDeferred<Unit>()

    // JSON codec for parsing messages
    private val jsonCodec: JsonCodec
        get() = moduleContext.jsonCodec

    // ========== Initialization ==========

    init {
        startActor()
    }

    private fun startActor() {
        actorJob = scope.launch {
            try {
                for (input in inputChannel) {
                    if (_isClosed && input !is SessionInput.Close) {
                        // Only process Close when closed, drop everything else
                        continue
                    }
                    try {
                        processInput(input)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Emit error but continue processing
                        _errors.tryEmit(SessionError.ProcessingError(url, e))
                    }
                }
            } finally {
                // Actor is done, signal close completion
                closeCompletion.complete(Unit)
            }
        }
    }

    private fun enqueueInput(input: SessionInput) {
        if (_isClosed && input !is SessionInput.Close) {
            return
        }

        val result = inputChannel.trySend(input)
        if (result.isFailure) {
            val inputType = input::class.simpleName ?: "unknown"
            _errors.tryEmit(SessionError.InputDropped(url, inputType))
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
        enqueueInput(SessionInput.Connect)
    }

    override fun disconnect(code: Int, reason: String?) {
        if (_isClosed) return
        enqueueInput(SessionInput.Disconnect(code, reason))
    }

    override fun close() {
        enqueueInput(SessionInput.Close)
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
            closeCallback = { id -> enqueueInput(SessionInput.CloseSubscription(id)) },
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

    // ========== Actor Input Processing ==========

    private fun processInput(input: SessionInput) {
        when (input) {
            // Transport events
            is SessionInput.TransportOpened -> handleTransportOpened()
            is SessionInput.TransportMessage -> handleTransportMessage(input.text)
            is SessionInput.TransportClosing -> { /* Ignore, wait for Closed */
            }

            is SessionInput.TransportClosed -> handleTransportClosed(input.info)
            is SessionInput.TransportFailed -> handleTransportFailed(input.error)

            // User commands
            is SessionInput.Connect -> handleConnect()
            is SessionInput.Disconnect -> handleDisconnect(input.code, input.reason)
            is SessionInput.Close -> handleClose()
            is SessionInput.Subscribe -> handleSubscribe(input)
            is SessionInput.Publish -> handlePublish(input)
            is SessionInput.CloseSubscription -> handleCloseSubscription(input.subscriptionId)

            // Internal events
            is SessionInput.TimerFired -> handleTimerFired(input.key)
            is SessionInput.CloseTimeout -> handleCloseTimeout(input.subscriptionId)
            is SessionInput.ReconnectNow -> handleReconnectNow()
            is SessionInput.RetryPublish -> handleRetryPublish(input.eventId)
            is SessionInput.RetrySubscription -> handleRetrySubscription(input.subscriptionId)
            is SessionInput.PendingJobCompleted -> pendingJobs.remove(input.job)
        }
    }

    // ========== Transport Lifecycle ==========

    private fun handleConnect() {
        if (_isClosed) return
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

        val connection = transportFactory.create(url)
        transport = connection

        connection.setListener(object : RelayConnectionListener {
            override fun onOpen() {
                enqueueInput(SessionInput.TransportOpened)
            }

            override fun onMessage(text: String) {
                enqueueInput(SessionInput.TransportMessage(text))
            }

            override fun onPong(payload: okio.ByteString?) {
                // Could track for keepalive, but not critical
            }

            override fun onClosing(info: CloseInfo) {
                enqueueInput(SessionInput.TransportClosing(info))
            }

            override fun onClosed(info: CloseInfo) {
                enqueueInput(SessionInput.TransportClosed(info))
            }

            override fun onFailure(error: TransportError) {
                enqueueInput(SessionInput.TransportFailed(error))
            }
        })

        connection.connect()
    }

    private fun handleTransportOpened() {
        _connectionStatus.value = ConnectionStatus.Connected
        reconnectAttempt = 0

        // Start ping loop if configured
        startPingLoop()

        // Drive engine with TransportOpened
        driveEngine(TransportInput.Opened)

        // Send all pending subscriptions (Starting) or retry interrupted ones
        for ((_, record) in subscriptions) {
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
        for ((_, record) in publishes) {
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

        // Stop ping loop
        stopPingLoop()

        // Drive engine with transport closed
        driveEngine(TransportInput.Closed(info))

        // Cancel all active timers
        cancelAllTimers()

        // Finalize any subscriptions that were closing
        finalizeClosingSubscriptions("Connection closed while closing")

        // Handle reconnection
        if (!intentionalDisconnect && !_isClosed) {
            attemptReconnect(null)
        } else {
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun handleTransportFailed(error: TransportError) {
        val currentTransport = transport
        transport = null
        currentTransport?.setListener(null)

        // Stop ping loop
        stopPingLoop()

        // Drive engine with transport failed
        driveEngine(TransportInput.Failed(error))

        // Cancel all active timers
        cancelAllTimers()

        // Finalize any subscriptions that were closing
        finalizeClosingSubscriptions("Connection failed while closing")

        // Handle reconnection
        if (!intentionalDisconnect && !_isClosed) {
            attemptReconnect(error)
        } else {
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    private fun handleDisconnect(code: Int, reason: String?) {
        intentionalDisconnect = true
        transport?.close(code, reason)
    }

    private fun handleClose() {
        if (_isClosed) return
        _isClosed = true
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

        // Close transport
        transport?.close()
        transport?.setListener(null)
        transport = null

        // Cancel all timers
        cancelAllTimers()

        // Close all subscription handles
        for ((_, record) in subscriptions) {
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

        // Clear deduplication cache
        deduplication.clear()

        // Update connection status
        _connectionStatus.value = ConnectionStatus.Disconnected

        // Close the input channel to stop the actor
        inputChannel.close()
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

                // Schedule reconnection with tracked job
                val job = scope.launch {
                    delay(decision.delayMs)
                    if (!_isClosed) {
                        enqueueInput(SessionInput.ReconnectNow)
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
        if (_connectionStatus.value == ConnectionStatus.Connected) return

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
        }

        // Store the subscription record
        val record = SubscriptionRecord(
            subscriptionId = input.subscriptionId,
            filters = input.filters,
            handle = input.handle,
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

    private fun handleCloseSubscription(subscriptionId: SubscriptionId) {
        val record = subscriptions[subscriptionId] ?: return
        if (record.handle.status.isTerminal) return

        if (_connectionStatus.value == ConnectionStatus.Connected) {
            driveEngine(Nip01Intent.CloseSubscription(subscriptionId))
            when (val policy = config.closePolicy) {
                ClosePolicy.Immediate -> {
                    record.handle.updateStatus(SubscriptionStatus.Closed(byRelay = false))
                    subscriptions.remove(subscriptionId)
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
            subscriptions.remove(subscriptionId)
            cancelCloseTimeout(subscriptionId)
            forgetSubscription(subscriptionId)
        }
    }

    private fun finalizeClosingSubscriptions(reason: String?) {
        val closingIds = subscriptions.values
            .filter { it.handle.status == SubscriptionStatus.Closing }
            .map { it.subscriptionId }

        for (subscriptionId in closingIds) {
            val record = subscriptions.remove(subscriptionId) ?: continue
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
        subscriptions.remove(subscriptionId)
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
                            enqueueInput(SessionInput.RetrySubscription(record.subscriptionId))
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
                subscriptions.remove(record.subscriptionId)
                forgetSubscription(record.subscriptionId)
            }
        }
    }

    private fun handleRetrySubscription(subscriptionId: SubscriptionId) {
        val record = subscriptions[subscriptionId] ?: return
        if (_connectionStatus.value != ConnectionStatus.Connected) return
        val status = record.handle.status
        if (status != SubscriptionStatus.Interrupted && status !is SubscriptionStatus.Retrying) return
        val reason = record.lastRetryReason ?: SubscriptionRetryReason.Disconnected
        retrySubscription(record, reason)
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
                            enqueueInput(SessionInput.RetryPublish(record.eventId))
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

    private fun handleRetryPublish(eventId: EventId) {
        val record = publishes[eventId] ?: return
        if (_connectionStatus.value != ConnectionStatus.Connected) return
        val status = record.handle.status
        if (status != PublishStatus.Interrupted && status !is PublishStatus.Retrying) return
        sendPublish(record)
    }

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
                val json = registry.wireCodec.encodeClientMessage(effect.message)
                val text = jsonCodec.stringify(json)
                val connection = transport
                if (connection == null) {
                    val error = TransportError.InvalidState("send", TransportState.Closed)
                    _errors.tryEmit(SessionError.SendFailed(url, error))
                    handleTransportFailed(error)
                    return
                }

                when (val result = connection.send(text)) {
                    is TransportResult.Success -> Unit
                    is TransportResult.Failure -> {
                        _errors.tryEmit(SessionError.SendFailed(url, result.error))
                        handleTransportFailed(result.error)
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

    private fun handleTransportMessage(text: String) {
        try {
            val jsonValue = jsonCodec.parse(text)
            val rawMessage = registry.wireCodec.decodeRelayMessage(jsonValue)
            val messageView = registry.messages.upgradeRelay(rawMessage)
            driveEngine(InboundMessage(messageView))
        } catch (e: Exception) {
            // Malformed message - emit error and continue
            _errors.tryEmit(SessionError.ParseError(url, text, e))
        }
    }

    // ========== Signal Processing ==========

    private fun processSignal(signal: ProtocolSignal) {
        when (signal) {
            is CoreSignal -> processCoreSignal(signal)
            is Nip01Signal -> processNip01Signal(signal)
            else -> {
                // Unknown signal type - could be from other NIPs
                // Subclasses or extensions can handle these
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
                        retryCount = 0,
                        lastRetryReason = null,
                    )
                    subscriptions[signal.subscriptionId] = updatedRecord
                    record.handle.updateStatus(SubscriptionStatus.CatchingUp)
                }
            }

            is Nip01Signal.SubscriptionEose -> {
                val record = subscriptions[signal.subscriptionId]
                if (record != null && record.generation == signal.generation) {
                    record.handle.updateStatus(SubscriptionStatus.Live)
                }
            }

            is Nip01Signal.SubscriptionEvent -> {
                val record = subscriptions[signal.subscriptionId]
                if (record != null && record.generation == signal.generation) {
                    // Check deduplication
                    if (!deduplication.isDuplicate(signal.event.id)) {
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
                        subscriptions.remove(signal.subscriptionId)
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

        pingJob = scope.launch {
            while (true) {
                delay(interval)
                if (_isClosed) break

                val connection = transport
                if (connection == null) break

                // Send ping - if this fails, the transport error handling will kick in
                val result = connection.ping(null)
                if (result is TransportResult.Failure) {
                    // Ping failed - trigger transport failure handling
                    enqueueInput(SessionInput.TransportFailed(result.error))
                    break
                }
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

    private fun parseCloseReason(message: String?): CloseReason? {
        if (message == null) return null

        return when {
            message.isAuthRequired() -> {
                val detail = message.authRequiredReason() ?: ""
                CloseReason.AuthRequired(detail, message)
            }
            message.isRestricted() -> {
                val detail = message.restrictedReason() ?: ""
                CloseReason.Restricted(detail, message)
            }
            else -> CloseReason.Other(message)
        }
    }
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
     * A message from the relay could not be parsed.
     *
     * @property rawMessage The raw message text that failed to parse.
     * @property cause The exception that occurred.
     */
    data class ParseError(
        override val relayUrl: RelayUrl,
        val rawMessage: String,
        val cause: Throwable,
    ) : SessionError()

    /**
     * An error occurred while processing an input.
     *
     * @property cause The exception that occurred.
     */
    data class ProcessingError(
        override val relayUrl: RelayUrl,
        val cause: Throwable,
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
