package io.github.nicolals.nostr.core.codec.wire

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.primitives.*

/**
 * Convert a domain [Event] to wire format [WireEvent].
 */
fun Event.toWireEvent(): WireEvent = WireEvent(
    id = id.hex,
    pubkey = pubkey.hex,
    createdAt = createdAt.value,
    kind = kind.value,
    tags = tags,
    content = content,
    sig = sig.hex,
)

/**
 * Convert a wire format [WireEvent] to domain [Event].
 * Returns null if any field fails validation.
 */
fun WireEvent.toEventOrNull(): Event? = runCatching {
    Event(
        id = EventId.fromHex(id),
        pubkey = PublicKey.fromHex(pubkey),
        createdAt = UnixTimeSeconds(createdAt),
        kind = EventKind(kind),
        tags = tags,
        content = content,
        sig = EventSig.fromHex(sig),
    )
}.getOrNull()
