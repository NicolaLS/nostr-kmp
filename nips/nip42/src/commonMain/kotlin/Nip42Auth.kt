package io.github.nicolals.nostr.nips.nip42

import nostr.core.crypto.Signer
import nostr.core.model.Event
import nostr.core.model.UnsignedEvent
import nostr.core.time.Clock
import nostr.core.time.SystemClock

/** Default normalization function applied to relay URLs when building authentication events. */
typealias RelayUrlNormalizer = (String) -> String

/** Convenience container for reusable NIP-42 defaults. */
object Nip42Defaults {
    /** Trims surrounding whitespace from relay URLs. */
    val normalizeRelayUrl: RelayUrlNormalizer = { value -> value.trim() }
}

/**
 * Utilities for constructing and inspecting NIP-42 authentication events (`kind: 22242`).
 *
 * The helpers keep challenge/relay tag management consistent and rely on [`nostr-core`][Signer]
 * primitives for signing.
 */
object Nip42Auth {
    /** Event kind reserved for NIP-42 ephemeral authentication events. */
    const val EVENT_KIND: Int = 22242

    /** Tag key referencing the relay URL in an authentication event. */
    const val TAG_RELAY: String = "relay"

    /** Tag key referencing the challenge string received from the relay. */
    const val TAG_CHALLENGE: String = "challenge"

    /**
     * Builds and signs a NIP-42 authentication event using [signer].
     *
     * @param signer Schnorr signer used to authorise the ephemeral authentication event.
     * @param relayUrl relay endpoint issuing the challenge.
     * @param challenge raw string supplied by the relay in the `AUTH` frame.
     * @param clock clock used to stamp the event timestamp; defaults to [SystemClock].
     * @param createdAt optional timestamp override (seconds). When `null`, [clock] provides the value.
     * @param content optional `content` payload included in the event body (defaults to an empty string).
     * @param additionalTags extra tags appended after the mandatory `relay` and `challenge` tags.
     * @param normalizer normalization function applied to [relayUrl] prior to encoding it into the event.
     */
    fun buildAuthEvent(
        signer: Signer,
        relayUrl: String,
        challenge: String,
        clock: Clock = SystemClock,
        createdAt: Long? = null,
        content: String = "",
        additionalTags: List<List<String>> = emptyList(),
        normalizer: RelayUrlNormalizer = Nip42Defaults.normalizeRelayUrl
    ): Event {
        val unsigned = buildUnsignedAuthEvent(
            pubkey = signer.publicKey.toString(),
            relayUrl = relayUrl,
            challenge = challenge,
            createdAt = createdAt ?: clock.nowSeconds(),
            content = content,
            additionalTags = additionalTags,
            normalizer = normalizer
        )
        return unsigned.sign(signer)
    }

    /**
     * Builds an unsigned NIP-42 authentication event for callers that want to sign using custom flows.
     *
     * @param pubkey lowercase hex public key authoring the authentication event.
     * @param relayUrl relay endpoint issuing the challenge.
     * @param challenge raw string supplied by the relay in the `AUTH` frame.
     * @param createdAt final timestamp for the event (seconds since epoch).
     * @param content optional body stored in the event `content` field.
     * @param additionalTags user-supplied tags appended after the mandatory `relay` and `challenge` tags.
     * @param normalizer normalization function applied to [relayUrl].
     */
    fun buildUnsignedAuthEvent(
        pubkey: String,
        relayUrl: String,
        challenge: String,
        createdAt: Long,
        content: String = "",
        additionalTags: List<List<String>> = emptyList(),
        normalizer: RelayUrlNormalizer = Nip42Defaults.normalizeRelayUrl
    ): UnsignedEvent {
        require(challenge.isNotBlank()) { "challenge must not be blank" }
        val normalizedRelay = normalizer(relayUrl)
        require(normalizedRelay.isNotBlank()) { "relayUrl must not be blank" }
        val tags = buildList(additionalTags.size + 2) {
            add(listOf(TAG_RELAY, normalizedRelay))
            add(listOf(TAG_CHALLENGE, challenge))
            additionalTags.forEach { tag ->
                require(tag.isNotEmpty()) { "additional tag entries must contain at least one value" }
                add(tag)
            }
        }
        return UnsignedEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = EVENT_KIND,
            tags = tags,
            content = content
        )
    }

    /** Returns `true` when [event] matches the NIP-42 authentication kind. */
    fun isAuthEvent(event: Event): Boolean = event.kind == EVENT_KIND

    /** Extracts the relay URL from the first `["relay", "..."]` tag in [event], if present. */
    fun relayUrl(event: Event): String? = event.tags.firstOrNull { it.firstOrNull() == TAG_RELAY }?.getOrNull(1)

    /** Extracts the challenge string from the first `["challenge", "..."]` tag in [event], if present. */
    fun challenge(event: Event): String? =
        event.tags.firstOrNull { it.firstOrNull() == TAG_CHALLENGE }?.getOrNull(1)
}

/**
 * Convenience extension mirroring [Nip42Auth.buildAuthEvent] for identity-aware callers.
 */
fun Signer.buildNip42AuthEvent(
    relayUrl: String,
    challenge: String,
    clock: Clock = SystemClock,
    createdAt: Long? = null,
    content: String = "",
    additionalTags: List<List<String>> = emptyList(),
    normalizer: RelayUrlNormalizer = Nip42Defaults.normalizeRelayUrl
): Event = Nip42Auth.buildAuthEvent(
    signer = this,
    relayUrl = relayUrl,
    challenge = challenge,
    clock = clock,
    createdAt = createdAt,
    content = content,
    additionalTags = additionalTags,
    normalizer = normalizer
)
