package io.github.nicolals.nostr.nip44.model

import okio.ByteString

data class Nip44MessageKeys(
    val chachaKey: ByteString,
    val chachaNonce: ByteString,
    val hmacKey: ByteString,
)
