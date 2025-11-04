package io.github.nicolals.nostr.client

import io.github.nicolals.nostr.nips.nip42.Nip42Auth
import io.github.nicolals.nostr.nips.nip42.Nip42Defaults
import io.github.nicolals.nostr.nips.nip42.RelayUrlNormalizer
import nostr.core.crypto.Signer
import nostr.core.model.Event
import nostr.core.time.Clock
import nostr.core.time.SystemClock

/** Strategy interface that constructs signed NIP-42 authentication events for a relay challenge. */
fun interface Nip42AuthProvider {
    /** Builds a signed authentication event for [relayUrl] and [challenge]. */
    suspend fun buildAuthEvent(relayUrl: String, challenge: String): Event
}

/**
 * Default [Nip42AuthProvider] backed by a single [Signer]. Callers can customise the generated
 * content or append additional tags by supplying lightweight lambdas.
 */
class SignerNip42AuthProvider constructor(
    private val signer: Signer,
    private val clock: Clock = SystemClock,
    private val normalizer: RelayUrlNormalizer = Nip42Defaults.normalizeRelayUrl,
    private val contentProvider: ((relayUrl: String, challenge: String) -> String)? = null,
    private val additionalTagsProvider: ((relayUrl: String, challenge: String) -> List<List<String>>)? = null
) : Nip42AuthProvider {

    override suspend fun buildAuthEvent(relayUrl: String, challenge: String): Event {
        val content = contentProvider?.invoke(relayUrl, challenge) ?: ""
        val additionalTags = additionalTagsProvider?.invoke(relayUrl, challenge) ?: emptyList()
        return Nip42Auth.buildAuthEvent(
            signer = signer,
            relayUrl = relayUrl,
            challenge = challenge,
            clock = clock,
            content = content,
            additionalTags = additionalTags,
            normalizer = normalizer
        )
    }
}

/** Convenience helper that converts a [Signer] into a [SignerNip42AuthProvider]. */
fun Signer.asNip42AuthProvider(
    clock: Clock = SystemClock,
    normalizer: RelayUrlNormalizer = Nip42Defaults.normalizeRelayUrl,
    contentProvider: ((relayUrl: String, challenge: String) -> String)? = null,
    additionalTagsProvider: ((relayUrl: String, challenge: String) -> List<List<String>>)? = null
): Nip42AuthProvider =
    SignerNip42AuthProvider(this, clock, normalizer, contentProvider, additionalTagsProvider)
