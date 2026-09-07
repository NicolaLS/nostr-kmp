package io.github.nicolals.nostr.runtime.coroutines

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.message.view.ClientMessageView
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.protocol.ProtocolInput
import io.github.nicolals.nostr.core.protocol.TimerKey
import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.TransportError
import io.github.nicolals.nostr.core.transport.TransportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * Internal inputs to the session's actor loop.
 *
 * All session operations are channeled through these sealed types to ensure
 * single-threaded state access. The actor processes these sequentially.
 */
internal sealed interface SessionInput {

    // ========== Transport Events ==========

    /** Transport connection opened */
    data class TransportOpened(val generation: Long) : SessionInput

    /** Transport received a message */
    data class TransportMessage(val generation: Long, val text: String) : SessionInput

    /** Transport closing (handshake started) */
    data class TransportClosing(val generation: Long, val info: CloseInfo) : SessionInput

    /** Transport fully closed */
    data class TransportClosed(val generation: Long, val info: CloseInfo) : SessionInput

    /** Transport failed */
    data class TransportFailed(val generation: Long, val error: TransportError) : SessionInput

    // ========== User Commands ==========

    /** User requested to connect */
    data object Connect : SessionInput

    /** User requested to disconnect */
    data class Disconnect(val code: Int, val reason: String?) : SessionInput

    /** User requested to close the session */
    data object Close : SessionInput

    /** User requested a subscription */
    data class Subscribe(
        val subscriptionId: SubscriptionId,
        val filters: List<Filter>,
        val handle: CoroutinesSubscriptionHandle,
    ) : SessionInput

    /** User requested to publish an event */
    data class Publish(
        val event: Event,
        val handle: CoroutinesPublishHandle,
    ) : SessionInput

    /** User requested to close a subscription */
    data class CloseSubscription(val handle: CoroutinesSubscriptionHandle) : SessionInput

    data class Submit(val input: ProtocolInput, val completion: CompletableDeferred<Unit>) : SessionInput

    data class Send(val message: ClientMessageView, val completion: CompletableDeferred<TransportResult<Unit>>) : SessionInput

    // ========== Internal Events ==========

    /** Timer fired */
    data class TimerFired(val key: TimerKey) : SessionInput

    /** Close timeout elapsed */
    data class CloseTimeout(val subscriptionId: SubscriptionId) : SessionInput

    /** Reconnect delay elapsed, attempt reconnection */
    data class ReconnectNow(val generation: Long) : SessionInput

    /** Retry a publish after delay */
    data class RetryPublish(val eventId: EventId, val handle: CoroutinesPublishHandle) : SessionInput

    /** Retry a subscription after reconnect */
    data class RetrySubscription(val subscriptionId: SubscriptionId, val handle: CoroutinesSubscriptionHandle) : SessionInput

    data class Ping(val generation: Long) : SessionInput

    /** Internal: pending job completed */
    data class PendingJobCompleted(val job: Job) : SessionInput
}
