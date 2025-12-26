package io.github.nicolals.nostr.core.event.assemble

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.id.EventIdComputer
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.types.HexKey
import okio.ByteString

object EventAssembler {
    fun <T : EventView> sign(
        template: EventTemplate<T>,
        pubkey: HexKey,
        sign: (b: ByteString) -> ByteString
    ): T {
        val idBytes = EventIdComputer.nip01IdBytes(
            pubkey = pubkey,
            createdAt = template.draft.createdAt,
            kind = template.draft.kind,
            tags = template.draft.tags,
            content = template.draft.content,
        )
        val sig = sign(idBytes)
        val event = Event(
            id = idBytes.hex(),
            pubkey = pubkey,
            createdAt = template.draft.createdAt,
            kind = template.draft.kind,
            tags = template.draft.tags,
            content = template.draft.content,
            sig = sig.hex(),
        )
        return template.wrap(event)
    }
}