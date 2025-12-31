package io.github.nicolals.nostr.nip44.model

import okio.ByteString

data class Nip44Payload(
    val nonce: ByteString,
    val ciphertext: ByteString,
    val mac: ByteString,
)
