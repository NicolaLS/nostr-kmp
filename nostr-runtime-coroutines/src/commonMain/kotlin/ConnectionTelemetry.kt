package nostr.runtime.coroutines

import nostr.core.session.ConnectionSnapshot

/**
 * Describes the most recent transport-level failure encountered by the runtime.
 *
 * @property url relay url that failed to connect or collapsed, when known.
 * @property message human-readable explanation surfaced by the transport stack.
 * @property attempt ordinal of the connection attempt that triggered the failure.
 */
data class ConnectionFailure(
    val url: String?,
    val message: String,
    val attempt: Int
)

/**
 * Aggregated connection telemetry exposed by [CoroutineNostrRuntime]. It mirrors the current
 * [snapshot] while enriching it with reconnect bookkeeping so downstream clients can render
 * connection health indicators without re-implementing reducer logic.
 *
 * @property snapshot latest connection snapshot from the reducer.
 * @property attempt number of connection attempts performed for the current relay url; resets
 * to `0` when the runtime is idle or switches to a new relay.
 * @property isRetrying convenience flag indicating that the runtime tried more than once.
 * @property lastFailure most recent failure emitted by the runtime, if any.
 */
data class ConnectionTelemetry(
    val snapshot: ConnectionSnapshot,
    val attempt: Int,
    val isRetrying: Boolean,
    val lastFailure: ConnectionFailure?
)
