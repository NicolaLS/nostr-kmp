package io.github.nicolals.nostr.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.core.model.Event
import nostr.core.session.ConnectionSnapshot
import nostr.core.session.Nip42AuthAttempt
import nostr.core.session.Nip42AuthState
import nostr.core.session.RelaySessionOutput
import nostr.core.session.RelaySessionState
import nostr.runtime.coroutines.RelaySessionManager

internal interface Nip42SessionHandle {
    val url: String
    val outputs: SharedFlow<RelaySessionOutput>
    val states: StateFlow<RelaySessionState>
    val connectionSnapshots: StateFlow<ConnectionSnapshot>
    suspend fun authenticate(event: Event)
}

data class NostrClientAutoAuthStatus(
    val challenge: String? = null,
    val attempt: Nip42AuthAttempt? = null,
    val isConnected: Boolean = false,
    val lastAuthRequiredId: String? = null,
    val lastAuthRequiredMessage: String? = null,
    val lastError: String? = null
)

internal class Nip42AutoAuthenticator(
    private val scope: CoroutineScope,
    private val session: Nip42SessionHandle,
    private val provider: Nip42AuthProvider
) {
    private val authMutex = Mutex()
    private val initialState = session.states.value.auth
    private var connectionState: ConnectionSnapshot = session.connectionSnapshots.value
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val authScope = CoroutineScope(scope.coroutineContext + supervisor)
    private val statusState = kotlinx.coroutines.flow.MutableStateFlow(
        NostrClientAutoAuthStatus(
            challenge = initialState.challenge,
            attempt = initialState.latestAttempt,
            isConnected = connectionState is ConnectionSnapshot.Connected
        )
    )
    val statuses: StateFlow<NostrClientAutoAuthStatus> = statusState.asStateFlow()

    val authStates: StateFlow<Nip42AuthState> = session.states
        .map { it.auth }
        .distinctUntilChanged()
        .stateIn(authScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, initialState)

    private val processorJob: Job

    init {
        processorJob = authScope.launch {
            val authJob = launch { authStates.collect { handleAuthState(it) } }
            val outputsJob = launch { session.outputs.collect { handleOutput(it) } }
            val connectionJob = launch { session.connectionSnapshots.collect { handleConnection(it) } }
            joinAll(authJob, outputsJob, connectionJob)
        }

        if (shouldAttempt(initialState, force = false) && connectionState is ConnectionSnapshot.Connected) {
            authScope.launch { triggerAuth(initialState, "initial") }
        }
    }

    suspend fun stop() {
        processorJob.cancelAndJoin()
        supervisor.cancel()
        supervisor.join()
    }

    private suspend fun handleAuthState(auth: Nip42AuthState) {
        statusState.update {
            it.copy(
                challenge = auth.challenge,
                attempt = auth.latestAttempt,
                lastError = if (auth.latestAttempt?.accepted == true) null else it.lastError
            )
        }
        if (connectionState !is ConnectionSnapshot.Connected) return
        if (shouldAttempt(auth, force = false)) {
            triggerAuth(auth, "state")
        }
    }

    private suspend fun handleOutput(output: RelaySessionOutput) {
        when (output) {
            is RelaySessionOutput.PublishAcknowledged -> {
                val attempt = authStates.value.latestAttempt
                if (attempt != null && attempt.eventId == output.result.eventId) {
                    return
                }
                if (output.result.code == AUTH_REQUIRED_CODE) {
                    statusState.update {
                        it.copy(
                            lastAuthRequiredId = output.result.eventId,
                            lastAuthRequiredMessage = output.result.message.ifBlank { null }
                        )
                    }
                    val auth = authStates.value
                    if (auth.challenge != null) {
                        triggerAuth(auth, "publish-auth-required", force = true)
                    }
                }
            }

            is RelaySessionOutput.SubscriptionTerminated -> {
                if (output.code == AUTH_REQUIRED_CODE) {
                    statusState.update {
                        it.copy(
                            lastAuthRequiredId = output.subscriptionId.value,
                            lastAuthRequiredMessage = output.reason.ifBlank { null }
                        )
                    }
                    val auth = authStates.value
                    if (auth.challenge != null) {
                        triggerAuth(auth, "subscription-auth-required", force = true)
                    }
                }
            }

            is RelaySessionOutput.AuthChallenge -> {
                statusState.update {
                    it.copy(challenge = output.challenge, lastError = null)
                }
            }

            else -> Unit
        }
    }

    private fun handleConnection(snapshot: ConnectionSnapshot) {
        connectionState = snapshot
        val connected = snapshot is ConnectionSnapshot.Connected
        statusState.update { it.copy(isConnected = connected) }
    }

    private suspend fun triggerAuth(auth: Nip42AuthState, reason: String, force: Boolean = false) {
        val challenge = auth.challenge ?: return
        if (connectionState !is ConnectionSnapshot.Connected) return
        val latestBefore = authStates.value
        if (!shouldAttempt(latestBefore, force)) {
            // State already satisfied or pending, no need to send.
            return
        }
        authMutex.withLock {
            val latest = authStates.value
            if (!shouldAttempt(latest, force)) return
            val event = runCatching { provider.buildAuthEvent(session.url, challenge) }
                .onFailure { failure ->
                    statusState.update {
                        it.copy(lastError = failure.message ?: failure::class.simpleName)
                    }
                }
                .getOrNull() ?: return
            val sendResult = runCatching { session.authenticate(event) }
            if (sendResult.isFailure) {
                statusState.update {
                    it.copy(lastError = sendResult.exceptionOrNull()?.message ?: sendResult.exceptionOrNull()?.let { ex ->
                        ex::class.simpleName
                    })
                }
            } else {
                statusState.update { it.copy(lastError = null) }
            }
        }
    }

    private fun shouldAttempt(auth: Nip42AuthState, force: Boolean): Boolean {
        val challenge = auth.challenge ?: return false
        val attempt = auth.latestAttempt ?: return true
        if (attempt.challenge != challenge) return true
        return when {
            attempt.accepted == false -> true
            force && attempt.accepted == true -> true
            else -> false
        }
    }

    companion object {
        private const val AUTH_REQUIRED_CODE = "auth-required"
    }
}

internal class ManagedSessionHandle(
    private val delegate: nostr.runtime.coroutines.RelaySessionManager.ManagedRelaySession
) : Nip42SessionHandle {
    override val url: String get() = delegate.url
    override val outputs: SharedFlow<RelaySessionOutput> get() = delegate.outputs
    override val states: StateFlow<RelaySessionState> get() = delegate.states
    override val connectionSnapshots: StateFlow<ConnectionSnapshot> get() = delegate.connectionSnapshots
    override suspend fun authenticate(event: Event) = delegate.authenticate(event)
}
