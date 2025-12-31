package io.github.nicolals.nostr.nip44

import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.crypto.NostrCrypto
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals

class Nip44ModuleTest {
    private val ctx = object : NipModuleContext {
        override val jsonCodec: JsonCodec = object : JsonCodec {
            override fun parse(json: String) = error("jsonCodec not used in nip44 tests")
            override fun stringify(value: io.github.nicolals.nostr.core.codec.JsonValue) =
                error("jsonCodec not used in nip44 tests")
        }
        override val crypto = NostrCrypto
    }

    @Test
    fun nip44VectorEncryptsAndDecrypts() {
        val conversationKey =
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".decodeHex()
        val nonce = "0000000000000000000000000000000000000000000000000000000000000001".decodeHex()
        val plaintext = "a"
        val expectedPayload =
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"

        val encrypted = ctx.encryptNip44Content(plaintext, conversationKey, nonce).getOrThrow()
        assertEquals(expectedPayload, encrypted)

        val decrypted = ctx.decryptNip44Content(expectedPayload, conversationKey).getOrThrow()
        assertEquals(plaintext, decrypted)
    }
}

private fun <T> CryptoResult<T>.getOrThrow(): T = when (this) {
    is CryptoResult.Ok -> value
    is CryptoResult.Err -> throw AssertionError("Crypto error: $error")
}
