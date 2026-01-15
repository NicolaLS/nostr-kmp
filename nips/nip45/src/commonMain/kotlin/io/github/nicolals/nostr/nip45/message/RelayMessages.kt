package io.github.nicolals.nostr.nip45.message

import io.github.nicolals.nostr.core.message.raw.RawRelayMessage
import io.github.nicolals.nostr.core.message.view.RelayMessageView
import io.github.nicolals.nostr.core.primitives.SubscriptionId

/**
 * Result of a COUNT request from the relay.
 *
 * @property count The event count (may be exact or approximate)
 * @property approximate If true, the count is probabilistic. Null means relay didn't specify.
 */
data class CountResult(
    val count: Long,
    val approximate: Boolean?,
)

/**
 * NIP-45 RELAY COUNT message - relay responds with event count.
 * Wire format: ["COUNT", <subscription_id>, {"count": <integer>, "approximate": <true|false>?}]
 *
 * The count object must contain a "count" field with an integer.
 * Relays may optionally include an "approximate" field indicating
 * whether the count is probabilistic.
 */
data class CountRelayMessage(
    override val raw: RawRelayMessage,
    /** The subscription/query ID as raw string */
    val subscriptionIdString: String,
    /** The domain-typed subscription ID (validated), null if validation failed */
    val subscriptionId: SubscriptionId?,
    /** The count result */
    val result: CountResult,
) : RelayMessageView {
    companion object {
        const val TOKEN = "COUNT"
    }
}
