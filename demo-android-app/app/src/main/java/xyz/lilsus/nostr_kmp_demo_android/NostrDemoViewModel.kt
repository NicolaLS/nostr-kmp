package xyz.lilsus.nostr_kmp_demo_android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.identity.Identity
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.SubscriptionId
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.EngineError
import nostr.core.session.RelaySessionOutput
import nostr.crypto.Identity as SecpIdentity
import nostr.runtime.coroutines.CoroutineNostrRuntime
import nostr.transport.ktor.KtorRelayConnectionFactory

private const val DEFAULT_RELAY_URL = "wss://relay.damus.io"
private const val DISCONNECT_TIMEOUT_MS = 5_000L
private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

class NostrDemoViewModel : ViewModel() {

    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codec = KotlinxSerializationWireCodec.default()
    private val runtime = CoroutineNostrRuntime(
        scope = runtimeScope,
        connectionFactory = KtorRelayConnectionFactory(runtimeScope),
        wireEncoder = codec,
        wireDecoder = codec
    )

    private val subscriptionId = "demo-${UUID.randomUUID().toString().take(8)}"
    private val subscriptionIdValue = SubscriptionId(subscriptionId)
    private val defaultFilter = Filter(kinds = setOf(1), limit = 100)
    private var identity: Identity? = null
    private var identitySubscriptionId: SubscriptionId? = null
    private var identityFilter: Filter? = null

    private val _uiState = MutableStateFlow(
        NostrDemoUiState(
            relay = DEFAULT_RELAY_URL,
            status = "Idle"
        )
    )
    val uiState: StateFlow<NostrDemoUiState> = _uiState.asStateFlow()

    init {
        observeOutputs()
        startConnection()
        createIdentity()
    }

    fun reconnect() {
        viewModelScope.launch {
            _uiState.update { it.copy(status = "Reconnecting…", error = null, notice = null) }
            val disconnectResult = runCatching { runtime.disconnect() }
            if (disconnectResult.isFailure) {
                val failure = disconnectResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        status = "Error",
                        error = failure?.message ?: failure?.let { err -> err::class.simpleName.orEmpty() }
                    )
                }
                return@launch
            }

            val disconnectedReached = withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                runtime.states.first { state ->
                    when (state.connection) {
                        is ConnectionSnapshot.Disconnected,
                        is ConnectionSnapshot.Failed -> true
                        else -> false
                    }
                }
            } != null

            if (!disconnectedReached) {
                _uiState.update {
                    it.copy(
                        status = "Error",
                        error = "Timed out waiting for disconnect"
                    )
                }
                return@launch
            }

            val connectResult = runCatching { runtime.connect(DEFAULT_RELAY_URL) }
            if (connectResult.isFailure) {
                val failure = connectResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        status = "Error",
                        error = failure?.message ?: failure?.let { err -> err::class.simpleName.orEmpty() }
                    )
                }
                return@launch
            }

            if (!subscribeToDefaultFeed()) return@launch
            subscribeToIdentityFeed()
        }
    }

    fun createIdentity() {
        viewModelScope.launch {
            val previousSubscriptionId = identitySubscriptionId
            val newIdentity = SecpIdentity.random()
            val publicKey = newIdentity.publicKey.toString()
            val newSubscriptionId = SubscriptionId("self-${UUID.randomUUID().toString().take(8)}")
            val newFilter = Filter(authors = setOf(publicKey), kinds = setOf(1), limit = 100)

            identity = newIdentity
            identitySubscriptionId = newSubscriptionId
            identityFilter = newFilter

            _uiState.update {
                it.copy(
                    identityPublicKey = publicKey,
                    ownEvents = emptyList(),
                    draftContent = "",
                    notice = "Generated new identity"
                )
            }

            previousSubscriptionId?.let { previous ->
                runCatching { runtime.unsubscribe(previous) }
            }
            subscribeToIdentityFeed()
        }
    }

    fun updateDraft(content: String) {
        _uiState.update { it.copy(draftContent = content) }
    }

    fun publishEvent() {
        viewModelScope.launch {
            val activeIdentity = identity
            if (activeIdentity == null) {
                _uiState.update { it.copy(error = "No identity available", notice = null) }
                return@launch
            }
            val content = uiState.value.draftContent.trim()
            if (content.isEmpty()) {
                _uiState.update { it.copy(error = "Event content cannot be empty", notice = null) }
                return@launch
            }

            val eventResult = runCatching {
                activeIdentity
                    .newEventBuilder()
                    .kind(1)
                    .content(content)
                    .build()
            }

            eventResult.onSuccess { event ->
                val publishResult = runCatching { runtime.publish(event) }
                if (publishResult.isSuccess) {
                    _uiState.update { it.copy(draftContent = "", notice = "Event published", error = null) }
                } else {
                    val failure = publishResult.exceptionOrNull()
                    _uiState.update {
                        it.copy(
                            error = failure?.message ?: failure?.let { err -> err::class.simpleName.orEmpty() },
                            notice = null
                        )
                    }
                }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(
                        error = failure.message ?: failure::class.simpleName.orEmpty(),
                        notice = null
                    )
                }
            }
        }
    }

    private fun observeOutputs() {
        viewModelScope.launch {
            runtime.outputs.collect { output -> handleOutput(output) }
        }
    }

    private fun startConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(status = "Connecting…", error = null) }
            val connectResult = runCatching { runtime.connect(DEFAULT_RELAY_URL) }
            if (connectResult.isFailure) {
                val failure = connectResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        status = "Error",
                        error = failure?.message ?: failure?.let { err -> err::class.simpleName.orEmpty() }
                    )
                }
                return@launch
            }
            if (!subscribeToDefaultFeed()) return@launch
            subscribeToIdentityFeed()
        }
    }

    private fun handleOutput(output: RelaySessionOutput) {
        when (output) {
            is RelaySessionOutput.ConnectionStateChanged -> {
                val status = when (val snapshot = output.snapshot) {
                    is ConnectionSnapshot.Connected -> "Connected"
                    is ConnectionSnapshot.Connecting -> "Connecting…"
                    is ConnectionSnapshot.Disconnecting -> "Disconnecting"
                    is ConnectionSnapshot.Disconnected -> "Disconnected"
                    is ConnectionSnapshot.Failed -> "Failed"
                }
                _uiState.update { it.copy(status = status, error = null) }
            }
            is RelaySessionOutput.EventReceived -> handleEvent(output)
            is RelaySessionOutput.EndOfStoredEvents -> {
                _uiState.update {
                    it.copy(notice = "Relay sent all stored events", error = null)
                }
            }
            is RelaySessionOutput.Notice -> {
                _uiState.update { it.copy(notice = output.message) }
            }
            is RelaySessionOutput.Error -> {
                val message = when (val error = output.error) {
                    is EngineError.ConnectionFailure -> error.message
                    is EngineError.ProtocolViolation -> error.description
                    is EngineError.OutboundFailure -> error.reason
                }
                _uiState.update { state ->
                    val newStatus = when (output.error) {
                        is EngineError.ConnectionFailure -> "Failed"
                        else -> state.status
                    }
                    state.copy(error = message, status = newStatus)
                }
            }
            is RelaySessionOutput.SubscriptionRegistered -> {
                _uiState.update {
                    it.copy(
                        notice = "Subscribed (${output.subscriptionId.value})",
                        error = null
                    )
                }
            }
            is RelaySessionOutput.SubscriptionTerminated -> {
                val reason = buildString {
                    append(output.reason)
                    output.code?.let { append(" [", it, "]") }
                }.ifBlank { "Relay closed subscription" }
                _uiState.update {
                    it.copy(
                        notice = "Subscription closed: $reason",
                        status = "Disconnected"
                    )
                }
            }
            is RelaySessionOutput.PublishAcknowledged -> {
                val notice = buildString {
                    append(if (output.result.accepted) "Publish accepted" else "Publish rejected")
                    output.result.code?.let { append(" (", it, ")") }
                    if (output.result.message.isNotBlank()) {
                        append(": ", output.result.message)
                    }
                }
                _uiState.update { it.copy(notice = notice) }
            }
            is RelaySessionOutput.AuthChallenge -> {
                _uiState.update { it.copy(notice = "Relay requested auth challenge") }
            }
            is RelaySessionOutput.CountResult -> {
                _uiState.update {
                    it.copy(notice = "Relay reported ${output.count} events for ${output.subscriptionId.value}")
                }
            }
        }
    }

    private suspend fun subscribeToIdentityFeed() {
        val subId = identitySubscriptionId
        val filter = identityFilter
        if (subId == null || filter == null) return
        val result = runCatching { runtime.subscribe(subId, listOf(filter)) }
        if (result.isFailure) {
            val failure = result.exceptionOrNull()
            _uiState.update {
                it.copy(
                    status = "Error",
                    error = failure?.message ?: failure?.let { err -> err::class.simpleName.orEmpty() }
                )
            }
        }
    }

    private suspend fun subscribeToDefaultFeed(): Boolean {
        val result = runCatching { runtime.subscribe(subscriptionIdValue, listOf(defaultFilter)) }
        if (result.isFailure) {
            val failure = result.exceptionOrNull()
            _uiState.update {
                it.copy(
                    status = "Error",
                    error = failure?.message ?: failure?.let { err -> err::class.simpleName.orEmpty() },
                    notice = null
                )
            }
            return false
        }
        return true
    }

    private fun handleEvent(output: RelaySessionOutput.EventReceived) {
        val event = output.event
        if (output.subscriptionId == identitySubscriptionId) {
            addOwnEvent(event)
        } else {
            addGlobalEvent(event)
        }
    }

    private fun addGlobalEvent(event: Event) {
        val display = DisplayEvent(
            id = event.id,
            author = event.pubkey,
            content = event.content.ifEmpty { "(empty message)" },
            timestamp = event.createdAt,
            formattedTime = formatTimestamp(event.createdAt)
        )
        _uiState.update { state ->
            val filtered = state.events.filterNot { it.id == display.id }
            val updated = listOf(display) + filtered
            state.copy(events = updated.take(100), error = null)
        }
    }

    private fun addOwnEvent(event: Event) {
        val display = DisplayEvent(
            id = event.id,
            author = event.pubkey,
            content = event.content.ifEmpty { "(empty message)" },
            timestamp = event.createdAt,
            formattedTime = formatTimestamp(event.createdAt)
        )
        _uiState.update { state ->
            val filtered = state.ownEvents.filterNot { it.id == display.id }
            val updated = listOf(display) + filtered
            state.copy(ownEvents = updated.take(100), error = null)
        }
    }

    private fun formatTimestamp(seconds: Long): String =
        runCatching {
            timeFormatter.format(Date(seconds * 1000))
        }.getOrDefault(seconds.toString())

    override fun onCleared() {
        super.onCleared()
        val shutdownJob = runtimeScope.launch {
            runCatching { runtime.shutdown() }
        }
        shutdownJob.invokeOnCompletion { runtimeScope.cancel() }
    }
}

data class NostrDemoUiState(
    val relay: String,
    val status: String,
    val events: List<DisplayEvent> = emptyList(),
    val ownEvents: List<DisplayEvent> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
    val identityPublicKey: String? = null,
    val draftContent: String = ""
)

data class DisplayEvent(
    val id: String,
    val author: String,
    val content: String,
    val timestamp: Long,
    val formattedTime: String
) {
    val authorShort: String =
        if (author.length <= 16) author else "${author.take(8)}…${author.takeLast(8)}"
}
