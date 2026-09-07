package io.github.nicolals.nostr.core.event.assemble

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.id.EventIdComputer
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.PublicKey
import okio.ByteString

/** Assembles an immutable draft with a local or asynchronous hash-signing capability. */
object EventAssembler {
    suspend fun sign(
        draft: EventDraft,
        pubkey: PublicKey,
        sign: suspend (ByteString) -> CryptoResult<ByteString>,
    ): CryptoResult<Event> {
        val idBytes = try {
            EventIdComputer.nip01IdBytes(pubkey, draft.createdAt, draft.kind, draft.tags, draft.content)
        } catch (_: IllegalArgumentException) {
            return CryptoResult.Err(CryptoError.SigningFailed("Invalid canonical event encoding"))
        }
        return sign(idBytes).flatMap { signature ->
            if (signature.size != 64) {
                CryptoResult.Err(CryptoError.SigningFailed("Expected a 64-byte Schnorr signature"))
            } else {
                CryptoResult.Ok(
                    Event(
                        id = EventId.fromBytes(idBytes),
                        pubkey = pubkey,
                        createdAt = draft.createdAt,
                        kind = draft.kind,
                        tags = draft.tags,
                        content = draft.content,
                        sig = EventSig.fromBytes(signature),
                    ),
                )
            }
        }
    }
}
