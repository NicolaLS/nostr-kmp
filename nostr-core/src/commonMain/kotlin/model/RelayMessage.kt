package nostr.core.model

/**
 * Result payload for relay `OK` frames.
 *
 * @property eventId canonical event identifier acknowledged by the relay.
 * @property accepted whether the relay accepted (`true`) or rejected (`false`) the publish.
 * @property message human-friendly message supplied by the relay (may be empty).
 * @property code optional machine-readable prefix such as `invalid`, `blocked`, or `error`.
 */
data class PublishResult(
    val eventId: String,
    val accepted: Boolean,
    val message: String,
    val code: String? = null
)

/** Machine-readable prefixes recommended by NIP-01 for relay acknowledgements. */
val KNOWN_ACK_PREFIXES = setOf(
    "duplicate",
    "pow",
    "blocked",
    "rate-limited",
    "invalid",
    "auth-required",
    "restricted",
    "mute",
    "error"
)

/** Relay-to-client messages defined by NIP-01 and a few common extensions. */
sealed interface RelayMessage {
    /** `EVENT` frame delivering [event] for [subscriptionId]. */
    data class Event(val subscriptionId: SubscriptionId, val event: nostr.core.model.Event) : RelayMessage

    /** `NOTICE` frame with arbitrary text supplied by the relay. */
    data class Notice(val message: String) : RelayMessage

    /** `EOSE` frame indicating replay is complete for [subscriptionId]. */
    data class EndOfStoredEvents(val subscriptionId: SubscriptionId) : RelayMessage

    /**
     * `CLOSED` frame acknowledging a subscription termination.
     *
     * @property subscriptionId identifier originally supplied in the client's `REQ` frame.
     * @property reason human-readable text sent by the relay (never `null`, may be empty).
     * @property code optional machine-readable prefix extracted from the relay message.
     */
    data class Closed(val subscriptionId: SubscriptionId, val reason: String, val code: String?) : RelayMessage

    /** `OK` frame confirming or rejecting a published event. */
    data class Ok(val result: PublishResult) : RelayMessage

    /** `AUTH` challenge payload (NIP-42). */
    data class AuthChallenge(val challenge: String) : RelayMessage

    /** `COUNT` response returning the matching event [count]. */
    data class Count(val subscriptionId: SubscriptionId, val count: Int) : RelayMessage

    /** Any frame the codec could not parse; retains [raw] payload and optional [cause]. */
    data class Unknown(val raw: String, val reason: String, val cause: Throwable? = null) : RelayMessage
}
