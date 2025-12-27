package io.github.nicolals.nostr.core.event.id

import io.github.nicolals.nostr.core.crypto.PublicKey
import io.github.nicolals.nostr.core.event.EventKind
import io.github.nicolals.nostr.core.event.EventTags
import io.github.nicolals.nostr.core.types.UnixTimeSeconds
import okio.ByteString

object EventIdComputer {
    fun nip01IdBytes(
        pubkey: PublicKey,
        createdAt: UnixTimeSeconds,
        kind: EventKind,
        tags: EventTags,
        content: String,
    ): ByteString {
        val b = EventIdSerializer.nip01SerializeToBuffer(pubkey, createdAt, kind, tags, content)
        return b.sha256()
    }

    fun nip01IdHex(
        pubkey: PublicKey,
        createdAt: UnixTimeSeconds,
        kind: EventKind,
        tags: EventTags,
        content: String,
    ) = nip01IdBytes(pubkey, createdAt, kind, tags, content).hex()
}