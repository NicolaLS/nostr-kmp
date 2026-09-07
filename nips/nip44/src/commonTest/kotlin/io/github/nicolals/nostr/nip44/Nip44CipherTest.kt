package io.github.nicolals.nostr.nip44

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrEventVerifier
import io.github.nicolals.nostr.crypto.NostrSigner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Nip44CipherTest {
    private val firstSecret = SecretKey.fromHex("01".padStart(64, '0'))
    private val secondSecret = SecretKey.fromHex("02".padStart(64, '0'))
    private val firstSigner = NostrSigner.fromSecretKey(firstSecret).getOrThrow()
    private val secondSigner = NostrSigner.fromSecretKey(secondSecret).getOrThrow()
    private val first: Nip44Cipher = LocalNip44Cipher(firstSecret, NostrCrypto)
    private val second: Nip44Cipher = LocalNip44Cipher(secondSecret, NostrCrypto)
    private val verifier = NostrEventVerifier()

    @Test
    fun capabilityRoundTripsWithoutExposingThePrivateKey() = runTest {
        val content = first.encrypt("private message 🦄", secondSigner.pubkey).getOrThrow()
        assertEquals("private message 🦄", second.decrypt(content, firstSigner.pubkey).getOrThrow())
        assertNotEquals(content, first.encrypt("private message 🦄", secondSigner.pubkey).getOrThrow())
        val event = firstSigner.sign(eventTemplate(EventKind(14), content)).getOrThrow().event
        assertEquals("private message 🦄", second.decryptEvent(event, verifier).getOrThrow())
        assertIs<CryptoResult.Err>(second.decryptEvent(event.copy(content = "unauthenticated"), verifier))
    }

    @Test
    fun invalidEventDoesNotReachTheCipher() = runTest {
        val content = first.encrypt("private", secondSigner.pubkey).getOrThrow()
        val event = firstSigner.sign(eventTemplate(EventKind(14), content)).getOrThrow().event
        val neverDecrypt = object : Nip44Cipher by second {
            override suspend fun decrypt(content: String, senderPublicKey: PublicKey): CryptoResult<String> =
                error("invalid event must not be decrypted")
        }
        assertIs<CryptoResult.Err>(neverDecrypt.decryptEvent(event.copy(content = "changed"), verifier))
    }

    @Test
    fun remoteCipherCancellationPropagates() = runTest {
        val event = firstSigner.sign(eventTemplate(EventKind(14), "content")).getOrThrow().event
        val started = CompletableDeferred<Unit>()
        var cleanupRan = false
        val remote = object : Nip44Cipher {
            override suspend fun encrypt(plaintext: String, recipientPublicKey: PublicKey): CryptoResult<String> =
                error("unused")
            override suspend fun decrypt(content: String, senderPublicKey: PublicKey): CryptoResult<String> {
                started.complete(Unit)
                try { awaitCancellation() } finally { cleanupRan = true }
            }
        }
        var returnedResult = false
        val job = launch {
            remote.decryptEvent(event, verifier)
            returnedResult = true
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(cleanupRan)
        assertFalse(returnedResult)
    }
}
