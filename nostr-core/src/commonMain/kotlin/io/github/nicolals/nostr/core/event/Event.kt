package io.github.nicolals.nostr.core.event

import io.github.nicolals.nostr.core.crypto.PublicKey
import io.github.nicolals.nostr.core.event.id.EventId


// TODO: In case this is used in Jetpack Compose it might make sense to declare @Immutable
// TODO: Make type aliases e.g EventKind @JvmInline value classes


/**
 * A signed Nostr event as defined by NIP-01.
 *
 * @property id 32-bytes lowercase hex-encoded sha256 of the serialized event data
 * @property pubkey 32-bytes lowercase hex-encoded public key of the event creator
 * @property createdAt Unix timestamp in seconds
 * @property kind Integer between 0 and 65535
 * @property tags List of lists with arbitrary strings
 * @property content Arbitrary string
 * @property sig 64-bytes lowercase hex of the signature of the sha256 hash of the serialized event data, which is the same as the "id" field
 */
data class Event(
    val id: EventId,
    val pubkey: PublicKey,
    val createdAt: Long,
    val kind: EventKind,
    val tags: EventTags,
    val content: String,
    val sig: EventSig,
)

