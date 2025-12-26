package io.github.nicolals.nostr.core.crypto

import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.types.HexKey
import okio.ByteString

interface EventTemplateSigner {
    val pubkey: HexKey
    fun <T : EventView> signEventTemplate(template: EventTemplate<T>): T
    fun signIdBytes(id: ByteString): ByteString
}
