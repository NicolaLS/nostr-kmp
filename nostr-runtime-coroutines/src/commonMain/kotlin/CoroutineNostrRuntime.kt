package nostr.runtime.coroutines

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import nostr.core.codec.WireDecoder
import nostr.core.codec.WireEncoder
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.session.*
import nostr.core.relay.RelayConnectionFactory

class CoroutineNostrRuntime(
    private val scope: CoroutineScope,
    private val connectionFactory: RelayConnectionFactory,
    private val wireEncoder: WireEncoder,
    private val wireDecoder: WireDecoder,
    settings: RelaySessionSettings = RelaySessionSettings(),
    reducer: RelaySessionReducer = DefaultRelaySessionReducer(),
    initialState: RelaySessionState = RelaySessionState()
) {
    private val engine = RelaySessionEngine(settings, reducer, initialState)
    private val stateFlow = MutableStateFlow(engine.state)
    private val outputFlow = MutableSharedFlow<RelaySessionOutput>(replay = 0, extraBufferCapacity = 64)
    private val intents = Channel<RelaySessionIntent>(Channel.UNLIMITED)
    private var connection: RelayConnectionAdapter? = null
    private var connectionJob: Job? = null
    private val processorJob: Job

    val states: StateFlow<RelaySessionState> = stateFlow.asStateFlow()
    val outputs: SharedFlow<RelaySessionOutput> = outputFlow.asSharedFlow()

    init {
        processorJob = scope.launch {
            processIntents()
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
                val transition = engine.dispatch(intent)
                stateFlow.value = transition.state
                handleCommands(transition.commands, queue, outputs)
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
        val previous = connection
        connection = null
        connectionJob?.cancelAndJoin()
        try {
            previous?.close(code = 1000, reason = null)
        } catch (_: Throwable) {
        } finally {
            previous?.dispose()
        }
        val created = try {
            RelayConnectionAdapter(connectionFactory.create(url))
        } catch (failure: Throwable) {
            queue.addLast(RelaySessionIntent.ConnectionFailed(url, failure.message ?: "Failed to create connection"))
            return
        }
        connection = created
        val openResult = kotlin.runCatching { created.open() }
        if (openResult.isFailure) {
            connection = null
            created.dispose()
            queue.addLast(
                RelaySessionIntent.ConnectionFailed(
                    url,
                    openResult.exceptionOrNull()?.message ?: "Failed to open connection"
                )
            )
            return
        }
        connectionJob = scope.launch {
            try {
                created.incoming.collect { frame ->
                    intents.send(RelaySessionIntent.RelayEvent(wireDecoder.relayMessage(frame)))
                }
                val closure = created.closeInfo()
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
                        created.url,
                        cause.message ?: "Incoming stream failed"
                    )
                )
            }
        }
        queue.addLast(RelaySessionIntent.ConnectionEstablished(url))
    }

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
