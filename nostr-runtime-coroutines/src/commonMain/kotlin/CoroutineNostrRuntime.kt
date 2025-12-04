package nostr.runtime.coroutines

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import nostr.core.codec.WireDecoder
import nostr.core.codec.WireEncoder
import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.RelayMessage
import nostr.core.model.SubscriptionId
import nostr.core.session.*
import nostr.core.relay.RelayConnectionFactory
import nostr.core.relay.HandshakeTimeoutException
import nostr.core.relay.IdleTimeoutException
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineNostrRuntime(
    private val scope: CoroutineScope,
    private val connectionFactory: RelayConnectionFactory,
    private val wireEncoder: WireEncoder,
    private val wireDecoder: WireDecoder,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    private val reconnectionPolicy: ReconnectionPolicy = NoReconnectionPolicy,
    settings: RelaySessionSettings = RelaySessionSettings(),
    reducer: RelaySessionReducer = DefaultRelaySessionReducer(),
    initialState: RelaySessionState = RelaySessionState(),
    interceptors: List<CoroutineRuntimeInterceptor> = emptyList()
) {
    private val runtimeJob: Job = SupervisorJob(scope.coroutineContext[Job])
    internal val runtimeScope: CoroutineScope = scope + runtimeJob
    private val engine = RelaySessionEngine(settings, reducer, initialState)
    private val stateFlow = MutableStateFlow(engine.state)
    private val outputFlow = MutableSharedFlow<RelaySessionOutput>(replay = 0, extraBufferCapacity = 64)
    private val intents = Channel<RelaySessionIntent>(Channel.BUFFERED)
    private val connectionSnapshotState = MutableStateFlow(engine.state.connection)
    private val telemetryState = MutableStateFlow(
        ConnectionTelemetry(
            snapshot = engine.state.connection,
            attempt = 0,
            isRetrying = false,
            lastFailure = null
        )
    )
    private var connection: RelayConnectionAdapter? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectAttemptToken: Long = 0
    private var activeUrl: String? = (engine.state.connection as? ConnectionSnapshot.Connected)?.url
    private var attemptCount: Int = 0
    private var lastFailure: ConnectionFailure? = null
    private val interceptors = interceptors.toList()
    private val processorJob: Job

    // Pending write confirmations keyed by event ID
    private val pendingWriteConfirmations = mutableMapOf<String, CompletableDeferred<WriteOutcome>>()

    val states: StateFlow<RelaySessionState> = stateFlow.asStateFlow()
    val outputs: SharedFlow<RelaySessionOutput> = outputFlow.asSharedFlow()
    val connectionSnapshots: StateFlow<ConnectionSnapshot> = connectionSnapshotState.asStateFlow()
    val connectionTelemetry: StateFlow<ConnectionTelemetry> = telemetryState.asStateFlow()

    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis cannot be negative" }
        processorJob = runtimeScope.launch {
            processIntents()
        }
    }

    private suspend fun notifyInterceptors(action: suspend (CoroutineRuntimeInterceptor) -> Unit) {
        for (interceptor in interceptors) {
            try {
                action(interceptor)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Ignore non-cancellation failures to keep runtime healthy
            }
        }
    }

    suspend fun connect(url: String) = dispatchIntent(RelaySessionIntent.Connect(url))

    suspend fun disconnect(code: Int? = 1000, reason: String? = null) =
        dispatchIntent(RelaySessionIntent.Disconnect(code, reason))

    suspend fun subscribe(subscriptionId: String, filters: List<Filter>) =
        dispatchIntent(RelaySessionIntent.Subscribe(SubscriptionId(subscriptionId), filters))

    suspend fun subscribe(subscriptionId: SubscriptionId, filters: List<Filter>) =
        dispatchIntent(RelaySessionIntent.Subscribe(subscriptionId, filters))

    suspend fun unsubscribe(subscriptionId: String) =
        dispatchIntent(RelaySessionIntent.Unsubscribe(SubscriptionId(subscriptionId)))

    suspend fun unsubscribe(subscriptionId: SubscriptionId) =
        dispatchIntent(RelaySessionIntent.Unsubscribe(subscriptionId))

    /**
     * Publishes an event to the relay and returns a [Deferred] for write confirmation.
     *
     * The returned [Deferred] completes with:
     * - [WriteOutcome.Success] when the frame is actually written to the socket
     * - [WriteOutcome.Failed] if the write fails (connection dead, encoding error, etc.)
     *
     * Users can:
     * - Ignore the result for fire-and-forget semantics (background apps)
     * - Await with timeout for fail-fast semantics (foreground apps):
     *   ```
     *   val outcome = withTimeoutOrNull(3000) { publish(event).await() }
     *       ?: WriteOutcome.Timeout
     *   ```
     *
     * @param event the event to publish
     * @return a Deferred that completes when the write is confirmed
     */
    suspend fun publish(event: Event): Deferred<WriteOutcome> {
        val confirmation = CompletableDeferred<WriteOutcome>()
        pendingWriteConfirmations[event.id] = confirmation
        dispatchIntent(RelaySessionIntent.Publish(event))
        return confirmation
    }

    suspend fun authenticate(event: Event) = dispatchIntent(RelaySessionIntent.Authenticate(event))

    suspend fun dispatchIntent(intent: RelaySessionIntent) {
        intents.send(intent)
    }

    suspend fun shutdown() {
        intents.close()
        processorJob.cancelAndJoin()
        reconnectJob?.cancel()
        reconnectJob = null
        connectionJob?.cancelAndJoin()
        val current = connection
        connection = null
        try {
            current?.close(code = 1000, reason = null)
        } catch (_: Throwable) {
        } finally {
            current?.dispose()
        }
        runtimeJob.cancel()
        runtimeJob.join()
    }

    private suspend fun processIntents() {
        val queue = ArrayDeque<RelaySessionIntent>()
        val outputs = mutableListOf<RelaySessionOutput>()
        try {
            while (true) {
                val intent = if (queue.isNotEmpty()) {
                    queue.removeFirst()
                } else {
                    intents.receiveCatching().getOrNull() ?: break
                }
                // Cancel pending reconnection when user explicitly changes connection state
                if (intent is RelaySessionIntent.Connect || intent is RelaySessionIntent.Disconnect) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                }
                if (intent is RelaySessionIntent.ConnectionFailed) {
                    recordFailure(intent)
                }
                val transition = engine.dispatch(intent)
                stateFlow.value = transition.state
                updateTelemetryFromState(transition.state)
                handleCommands(transition.commands, queue, outputs)
                refreshTelemetry()
                if (outputs.isNotEmpty()) {
                    outputs.forEach { outputFlow.emit(it) }
                    outputs.clear()
                }
                // Schedule reconnection if needed after processing connection failures or closes
                maybeScheduleReconnect(transition.state, intent)
            }
        } catch (_: CancellationException) {
            // shutting down
        }
    }

    private fun maybeScheduleReconnect(state: RelaySessionState, intent: RelaySessionIntent) {
        // Only consider reconnection after failure or unexpected close
        val shouldConsiderReconnect = when (intent) {
            is RelaySessionIntent.ConnectionFailed -> true
            is RelaySessionIntent.ConnectionClosed -> true
            else -> false
        }
        if (!shouldConsiderReconnect) return

        // Don't reconnect if user no longer wants a connection
        val desiredUrl = state.desiredRelayUrl ?: return

        // Don't reconnect if already connected or connecting
        when (state.connection) {
            is ConnectionSnapshot.Connected, is ConnectionSnapshot.Connecting -> return
            else -> Unit
        }

        // Consult the reconnection policy
        val delay = reconnectionPolicy.nextDelay(attemptCount, lastFailure) ?: return

        // Cancel any existing reconnect job before scheduling a new one
        reconnectJob?.cancel()
        reconnectJob = runtimeScope.launch {
            delay(delay)
            // Re-check that we still want to reconnect after the delay
            val currentState = stateFlow.value
            if (currentState.desiredRelayUrl == desiredUrl &&
                currentState.connection !is ConnectionSnapshot.Connected &&
                currentState.connection !is ConnectionSnapshot.Connecting
            ) {
                intents.send(RelaySessionIntent.Connect(desiredUrl))
            }
        }
    }

    private suspend fun handleCommands(
        commands: List<RelaySessionCommand>,
        queue: ArrayDeque<RelaySessionIntent>,
        outputs: MutableList<RelaySessionOutput>
    ) {
        for (command in commands) {
            when (command) {
                is RelaySessionCommand.OpenConnection -> openConnection(command.url, queue)
                is RelaySessionCommand.CloseConnection -> closeConnection(command.code, command.reason)
                is RelaySessionCommand.SendToRelay -> sendFrame(command, queue)
                is RelaySessionCommand.EmitOutput -> outputs += command.output
            }
        }
    }

    private suspend fun openConnection(url: String, queue: ArrayDeque<RelaySessionIntent>) {
        // Note: We intentionally don't guard against reconnecting to the same URL here.
        // The reducer already prevents duplicate connections when already Connected.
        // The connectAttemptToken mechanism handles stale connection results.
        // Removing this guard fixes the bug where connection field was stale after failure.
        if (activeUrl != url) {
            activeUrl = url
            attemptCount = 0
            lastFailure = null
        }
        attemptCount += 1
        connectAttemptToken += 1
        val attemptToken = connectAttemptToken
        refreshTelemetry(stateFlow.value.connection)
        val previous = connection
        connection = null
        connectionJob?.cancelAndJoin()
        try {
            previous?.close(code = 1000, reason = null)
        } catch (_: Throwable) {
        } finally {
            previous?.dispose()
        }
        notifyInterceptors { it.onConnectionOpening(url) }
        val created = try {
            RelayConnectionAdapter(delegate = connectionFactory.create(url))
        } catch (failure: Throwable) {
            queue.addLast(
                RelaySessionIntent.ConnectionFailed(
                    url = url,
                    reason = ConnectionFailureReason.ConnectionFactory,
                    message = failure.describeMessage("Failed to create connection"),
                    cause = failure.describeCause()
                )
            )
            return
        }
        connection = created
        connectionJob = runtimeScope.launch { runConnectionAttempt(created, url, attemptToken) }
    }

    private suspend fun runConnectionAttempt(adapter: RelayConnectionAdapter, url: String, attemptToken: Long) {
        val openResult = raceHandshake(adapter, url)
        if (attemptToken != connectAttemptToken) {
            adapter.dispose()
            return
        }
        if (openResult.isFailure) {
            connection = null
            adapter.dispose()
            val failure = openResult.exceptionOrNull()
            intents.send(
                RelaySessionIntent.ConnectionFailed(
                    url = url,
                    reason = ConnectionFailureReason.OpenHandshake,
                    message = failure?.describeMessage("Failed to open connection") ?: "Failed to open connection",
                    cause = failure?.describeCause()
                )
            )
            return
        }
        notifyInterceptors { it.onConnectionEstablished(url) }
        intents.send(RelaySessionIntent.ConnectionEstablished(url))
        val incomingResult = runConnected(adapter, url)
        if (attemptToken != connectAttemptToken) {
            adapter.dispose()
            return
        }
        // Clear the connection reference now that the stream has ended.
        // This is critical: without clearing, subsequent reconnect attempts
        // would see a stale adapter and potentially misbehave.
        if (connection === adapter) {
            connection = null
        }
        when (incomingResult) {
            is ConnectionOutcome.Closed -> {
                notifyInterceptors { it.onConnectionClosed(url, incomingResult.code, incomingResult.reason) }
                intents.send(
                    RelaySessionIntent.ConnectionClosed(
                        url,
                        incomingResult.code ?: 1000,
                        incomingResult.reason ?: "EOF"
                    )
                )
            }

            is ConnectionOutcome.Failed -> {
                val failure = incomingResult.cause
                intents.send(
                    RelaySessionIntent.ConnectionFailed(
                        url = url,
                        reason = incomingResult.reason,
                        message = failure.describeMessage("Incoming stream failed"),
                        cause = failure.describeCause()
                    )
                )
            }
        }
    }

    private suspend fun raceHandshake(adapter: RelayConnectionAdapter, url: String): Result<Unit> =
        coroutineScope {
            val opener = async { runCatching { adapter.open() } }
            val outcome = withTimeoutOrNull(connectTimeoutMillis) { opener.await() }
            when {
                outcome == null -> {
                    opener.cancel()
                    Result.failure(HandshakeTimeoutException(url, connectTimeoutMillis))
                }

                outcome.isFailure -> outcome
                else -> Result.success(Unit)
            }
        }

    private suspend fun runConnected(
        adapter: RelayConnectionAdapter,
        url: String
    ): ConnectionOutcome = coroutineScope {
        val channel = adapter.incoming.produceIn(this)
        try {
            while (true) {
                val frame = select<String?> {
                    channel.onReceiveCatching { it.getOrNull() }
                    if (readTimeoutMillis > 0) {
                        onTimeout(readTimeoutMillis) { throw IdleTimeoutException(url, readTimeoutMillis) }
                    }
                }
                if (frame == null) break
                val relayMessage = wireDecoder.relayMessage(frame)
                notifyInterceptors { it.onMessageReceived(adapter.url, relayMessage) }
                intents.send(RelaySessionIntent.RelayEvent(relayMessage))
            }
            // Check if the channel closed due to an underlying failure
            val underlyingFailure = adapter.failure()
            if (underlyingFailure != null) {
                return@coroutineScope ConnectionOutcome.Failed(underlyingFailure, ConnectionFailureReason.StreamFailure)
            }
            val closure = adapter.closeInfo()
            ConnectionOutcome.Closed(closure?.code, closure?.reason)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val cause = adapter.failure() ?: failure
            ConnectionOutcome.Failed(cause, ConnectionFailureReason.StreamFailure)
        } finally {
            channel.cancel()
            adapter.dispose()
        }
    }

    private sealed interface ConnectionOutcome {
        data class Closed(val code: Int?, val reason: String?) : ConnectionOutcome
        data class Failed(val cause: Throwable, val reason: ConnectionFailureReason) : ConnectionOutcome
    }

    private fun isCurrentConnection(adapter: RelayConnectionAdapter): Boolean = connection === adapter

    private fun recordFailure(intent: RelaySessionIntent.ConnectionFailed) {
        val failureUrl = intent.url ?: activeUrl
        lastFailure = ConnectionFailure(
            url = failureUrl,
            reason = intent.reason,
            message = intent.message,
            attempt = attemptCount,
            closeCode = intent.closeCode,
            closeReason = intent.closeReason,
            cause = intent.cause
        )
        refreshTelemetry(connectionSnapshotState.value)
    }

    private fun updateTelemetryFromState(state: RelaySessionState) {
        val snapshot = state.connection
        when (snapshot) {
            is ConnectionSnapshot.Connected -> {
                activeUrl = snapshot.url
            }

            is ConnectionSnapshot.Connecting -> {
                if (activeUrl == null) {
                    activeUrl = snapshot.url
                }
            }

            is ConnectionSnapshot.Disconnected -> {
                if (state.desiredRelayUrl == null) {
                    activeUrl = null
                    attemptCount = 0
                    lastFailure = null
                }
            }

            is ConnectionSnapshot.Disconnecting -> Unit
            is ConnectionSnapshot.Failed -> {
                if (snapshot.url != null) {
                    activeUrl = snapshot.url
                }
            }
        }
        publishTelemetry(snapshot)
    }

    private fun refreshTelemetry(snapshot: ConnectionSnapshot = connectionSnapshotState.value) {
        publishTelemetry(snapshot)
    }

    private fun publishTelemetry(snapshot: ConnectionSnapshot) {
        connectionSnapshotState.value = snapshot
        telemetryState.value = ConnectionTelemetry(
            snapshot = snapshot,
            attempt = attemptCount,
            isRetrying = attemptCount > 1,
            lastFailure = lastFailure
        )
    }

    private fun Throwable.describeMessage(default: String): String {
        val custom = message?.takeIf { it.isNotBlank() }
        if (custom != null) return custom
        val type = this::class.simpleName ?: this::class.qualifiedName
        return type ?: default
    }

    private fun Throwable.describeCause(): String? =
        this::class.qualifiedName ?: this::class.simpleName

    private suspend fun closeConnection(code: Int?, reason: String?) {
        val current = connection ?: return
        val job = connectionJob
        connection = null
        connectionJob = null
        val closeResult = kotlin.runCatching { current.close(code ?: 1000, reason) }
        if (job != null) {
            if (closeResult.isFailure) {
                job.cancelAndJoin()
            } else {
                job.join()
            }
        }
        current.dispose()
    }

    private suspend fun sendFrame(command: RelaySessionCommand.SendToRelay, queue: ArrayDeque<RelaySessionIntent>) {
        val current = connection

        // Extract event ID if this is a publish command (for write confirmation)
        val eventId = (command.message as? ClientMessage.Event)?.event?.id

        if (current == null) {
            queue.addLast(RelaySessionIntent.OutboundFailure(command, "Connection not available"))
            eventId?.let { completeWriteConfirmation(it, WriteOutcome.Failed(IllegalStateException("Connection not available"))) }
            return
        }

        notifyInterceptors { it.onSend(current.url, command.message) }

        val payload = kotlin.runCatching { wireEncoder.clientMessage(command.message) }
        if (payload.isFailure) {
            val error = payload.exceptionOrNull()
            queue.addLast(
                RelaySessionIntent.OutboundFailure(
                    command,
                    error?.message ?: "Failed to encode client message"
                )
            )
            eventId?.let { completeWriteConfirmation(it, WriteOutcome.Failed(error ?: IllegalStateException("Encoding failed"))) }
            return
        }

        // Send returns a Deferred<WriteOutcome> that completes when write is confirmed
        val writeConfirmation = current.send(payload.getOrThrow())

        // If this is a publish, forward the write confirmation to the pending deferred
        if (eventId != null) {
            runtimeScope.launch {
                val outcome = writeConfirmation.await()
                completeWriteConfirmation(eventId, outcome)
                if (outcome is WriteOutcome.Failed) {
                    // Also report as outbound failure for state tracking
                    intents.trySend(
                        RelaySessionIntent.OutboundFailure(command, outcome.cause.message ?: "Write failed")
                    )
                }
            }
        }
    }

    private fun completeWriteConfirmation(eventId: String, outcome: WriteOutcome) {
        pendingWriteConfirmations.remove(eventId)?.complete(outcome)
    }
}
