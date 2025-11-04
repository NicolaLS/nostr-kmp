package nostr.runtime.coroutines

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import nostr.core.codec.WireDecoder
import nostr.core.codec.WireEncoder
import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.RelayMessage
import nostr.core.model.SubscriptionId
import nostr.core.session.*
import nostr.core.relay.RelayConnectionFactory
import kotlin.coroutines.cancellation.CancellationException

class CoroutineNostrRuntime(
    private val scope: CoroutineScope,
    private val connectionFactory: RelayConnectionFactory,
    private val wireEncoder: WireEncoder,
    private val wireDecoder: WireDecoder,
    settings: RelaySessionSettings = RelaySessionSettings(),
    reducer: RelaySessionReducer = DefaultRelaySessionReducer(),
    initialState: RelaySessionState = RelaySessionState(),
    interceptors: List<CoroutineRuntimeInterceptor> = emptyList()
) {
    private val engine = RelaySessionEngine(settings, reducer, initialState)
    private val stateFlow = MutableStateFlow(engine.state)
    private val outputFlow = MutableSharedFlow<RelaySessionOutput>(replay = 0, extraBufferCapacity = 64)
    private val intents = Channel<RelaySessionIntent>(Channel.UNLIMITED)
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
    private var activeUrl: String? = (engine.state.connection as? ConnectionSnapshot.Connected)?.url
    private var attemptCount: Int = 0
    private var lastFailure: ConnectionFailure? = null
    private val interceptors = interceptors.toList()
    private val processorJob: Job

    val states: StateFlow<RelaySessionState> = stateFlow.asStateFlow()
    val outputs: SharedFlow<RelaySessionOutput> = outputFlow.asSharedFlow()
    val connectionSnapshots: StateFlow<ConnectionSnapshot> = connectionSnapshotState.asStateFlow()
    val connectionTelemetry: StateFlow<ConnectionTelemetry> = telemetryState.asStateFlow()

    init {
        processorJob = scope.launch {
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

    suspend fun publish(event: Event) = dispatchIntent(RelaySessionIntent.Publish(event))

    suspend fun authenticate(event: Event) = dispatchIntent(RelaySessionIntent.Authenticate(event))

    suspend fun dispatchIntent(intent: RelaySessionIntent) {
        intents.send(intent)
    }

    suspend fun shutdown() {
        intents.close()
        processorJob.cancelAndJoin()
        connectionJob?.cancelAndJoin()
        val current = connection
        connection = null
        try {
            current?.close(code = 1000, reason = null)
        } catch (_: Throwable) {
        } finally {
            current?.dispose()
        }
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
            }
        } catch (_: CancellationException) {
            // shutting down
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
        if (connection?.url == url) return
        if (activeUrl != url) {
            activeUrl = url
            attemptCount = 0
            lastFailure = null
        }
        attemptCount += 1
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
            RelayConnectionAdapter(connectionFactory.create(url))
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
        val openResult = kotlin.runCatching { created.open() }
        if (openResult.isFailure) {
            connection = null
            created.dispose()
            queue.addLast(
                RelaySessionIntent.ConnectionFailed(
                    url = url,
                    reason = ConnectionFailureReason.OpenHandshake,
                    message = openResult.exceptionOrNull()
                        ?.describeMessage("Failed to open connection")
                        ?: "Failed to open connection",
                    cause = openResult.exceptionOrNull()?.describeCause()
                )
            )
            return
        }
        notifyInterceptors { it.onConnectionEstablished(url) }
        connectionJob = scope.launch {
            try {
                created.incoming.collect { frame ->
                    val relayMessage = wireDecoder.relayMessage(frame)
                    notifyInterceptors { it.onMessageReceived(created.url, relayMessage) }
                    intents.send(RelaySessionIntent.RelayEvent(relayMessage))
                }
                val closure = created.closeInfo()
                notifyInterceptors {
                    it.onConnectionClosed(created.url, closure?.code, closure?.reason)
                }
                intents.send(
                    RelaySessionIntent.ConnectionClosed(
                        created.url,
                        closure?.code ?: 1000,
                        closure?.reason ?: "EOF"
                    )
                )
            } catch (_: CancellationException) {
                // moving away from this connection
            } catch (failure: Throwable) {
                val cause = created.failure() ?: failure
                intents.send(
                    RelaySessionIntent.ConnectionFailed(
                        url = created.url,
                        reason = ConnectionFailureReason.StreamFailure,
                        message = cause.describeMessage("Incoming stream failed"),
                        cause = cause.describeCause()
                    )
                )
            }
        }
        queue.addLast(RelaySessionIntent.ConnectionEstablished(url))
    }

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
        if (current == null) {
            queue.addLast(RelaySessionIntent.OutboundFailure(command, "Connection not available"))
            return
        }
        notifyInterceptors { it.onSend(current.url, command.message) }
        val payload = kotlin.runCatching { wireEncoder.clientMessage(command.message) }
        if (payload.isFailure) {
            queue.addLast(
                RelaySessionIntent.OutboundFailure(
                    command,
                    payload.exceptionOrNull()?.message ?: "Failed to encode client message"
                )
            )
            return
        }
        val result = kotlin.runCatching { current.send(payload.getOrThrow()) }
        if (result.isFailure) {
            queue.addLast(
                RelaySessionIntent.OutboundFailure(
                    command,
                    result.exceptionOrNull()?.message ?: "Failed to send frame"
                )
            )
        }
    }
}
