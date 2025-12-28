package io.github.nicolals.nostr.core.crypto

import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.primitives.PublicKey
import okio.ByteString

interface EventTemplateSigner {
    val pubkey: PublicKey
    fun <T : EventView> signEventTemplate(template: EventTemplate<T>): T
    fun signIdBytes(id: ByteString): ByteString
}
