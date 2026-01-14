package io.github.nicolals.nostr.nip42.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds

/**
 * NIP-42 authentication event (kind 22242).
 *
 * This ephemeral event is signed by clients to authenticate to relays.
 * It contains a "relay" tag with the relay URL and a "challenge" tag with
 * the challenge string received from the relay.
 *
 * This event type should NOT be stored or broadcasted by relays.
 */
class AuthEvent(
    override val event: Event,
    /** The relay URL from the "relay" tag */
    val relayUrl: String,
    /** The challenge string from the "challenge" tag */
    val challenge: String,
) : EventView {

    companion object {
        /** Kind 22242 - ephemeral authentication event */
        val KIND = EventKind(22242)

        private const val TAG_RELAY = "relay"
        private const val TAG_CHALLENGE = "challenge"

        /**
         * Create an AuthEvent from a signed Event.
         * Returns null if the event is not a valid auth event.
         */
        fun fromEventOrNull(event: Event): AuthEvent? {
            if (event.kind != KIND) return null

            val relayUrl = event.tags
                .firstOrNull { it.firstOrNull() == TAG_RELAY }
                ?.getOrNull(1)
                ?: return null

            val challenge = event.tags
                .firstOrNull { it.firstOrNull() == TAG_CHALLENGE }
                ?.getOrNull(1)
                ?: return null

            return AuthEvent(event, relayUrl, challenge)
        }

        /**
         * Create an unsigned template for an authentication event.
         *
         * @param relayUrl The URL of the relay being authenticated to
         * @param challenge The challenge string received from the relay
         * @param createdAt The event creation timestamp (should be current time)
         * @param extraTags Additional tags to include (optional)
         */
        fun template(
            relayUrl: String,
            challenge: String,
            createdAt: UnixTimeSeconds,
            extraTags: List<List<String>> = emptyList(),
        ): EventTemplate<AuthEvent> {
            val tags = buildList {
                add(listOf(TAG_RELAY, relayUrl))
                add(listOf(TAG_CHALLENGE, challenge))
                addAll(extraTags)
            }

            val draft = EventDraft(
                createdAt = createdAt,
                kind = KIND,
                tags = tags,
                content = "",
            )

            return EventTemplate(draft) { event ->
                AuthEvent(event, relayUrl, challenge)
            }
        }
    }
}
