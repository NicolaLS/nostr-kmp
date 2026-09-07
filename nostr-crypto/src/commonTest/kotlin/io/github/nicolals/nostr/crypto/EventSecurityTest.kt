package io.github.nicolals.nostr.crypto

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventTemplateSigner
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.assemble.EventAssembler
import io.github.nicolals.nostr.core.event.id.EventIdComputer
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventSig
import io.github.nicolals.nostr.core.primitives.KeyPair
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventSecurityTest {
    private val secret = SecretKey.fromHex("01".padStart(64, '0'))
    private val signer = NostrSigner.fromSecretKey(secret).getOrThrow()
    private val verifier = NostrEventVerifier()

    @Test
    fun tagsRemainStableWhileSigningSuspends() = runTest {
        val tag = mutableListOf("t", "original")
        val tags = mutableListOf<List<String>>(tag)
        val draft = EventDraft(UnixTimeSeconds(1), EventKind(1), tags, "hello")
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val signing = async {
            EventAssembler.sign(draft, signer.pubkey) { id ->
                started.complete(Unit)
                resume.await()
                NostrSigning.schnorrSign(secret.bytes, id)
            }
        }
        started.await()
        tag[1] = "changed during signing"
        tags.clear()
        resume.complete(Unit)
        val signed = signing.await().getOrThrow()
        assertEquals(listOf(listOf("t", "original")), signed.tags)
        assertIs<CryptoResult.Ok<Unit>>(verifier.verify(signed))
        assertTrue(runCatching { (signed.tags as MutableList<List<String>>).clear() }.isFailure)
        assertTrue(runCatching { (signed.tags[0] as MutableList<String>)[1] = "changed" }.isFailure)
        assertIs<CryptoResult.Ok<Unit>>(verifier.verify(signed))
    }

    @Test
    fun copiedAndDecodedEventsSnapshotCallerTags() = runTest {
        val event = signer.sign(eventTemplate(EventKind(1), "hello")).getOrThrow().event
        val tag = mutableListOf("t", "original")
        val tags = mutableListOf<List<String>>(tag)
        val copy = event.copy(tags = tags)
        val hash = copy.hashCode()
        tag.clear()
        tags.clear()
        assertEquals(listOf(listOf("t", "original")), copy.tags)
        assertEquals(hash, copy.hashCode())
        assertEquals(copy, copy.copy())
    }

    @Test
    fun verifiesCanonicalIdAndSignatureForAllAuthenticatedFields() = runTest {
        val original = signer.sign(eventTemplate(EventKind(1), "hello", listOf(listOf("t", "a")))).getOrThrow().event
        assertIs<CryptoResult.Ok<Unit>>(verifier.verify(original))
        val changedContent = original.copy(content = "changed")
        val changedContentWithRehashedId = changedContent.copy(id = EventId.fromBytes(
            EventIdComputer.nip01IdBytes(
                changedContent.pubkey, changedContent.createdAt, changedContent.kind,
                changedContent.tags, changedContent.content,
            ),
        ))
        val invalid = listOf(
            changedContent,
            changedContentWithRehashedId,
            original.copy(id = EventId.fromBytes(ByteArray(32).toByteString())),
            original.copy(sig = EventSig.fromBytes(ByteArray(64).toByteString())),
            original.copy(createdAt = UnixTimeSeconds(original.createdAt.value + 1)),
            original.copy(kind = EventKind(2)),
            original.copy(tags = listOf(listOf("t", "b"))),
            original.copy(content = "\u0000"),
            original.copy(content = "\uD800"),
        )
        for (event in invalid) assertIs<CryptoResult.Err>(verifier.verify(event))
    }

    @Test
    fun typedSigningPropagatesFailuresAndRejectsDifferentDrafts() = runTest {
        val template = eventTemplate(EventKind(1), "hello")
        val rejecting = object : EventTemplateSigner {
            override val pubkey = signer.pubkey
            override suspend fun sign(draft: EventDraft): CryptoResult<Event> =
                CryptoResult.Err(CryptoError.SigningFailed("Rejected by user"))
        }
        assertIs<CryptoResult.Err>(rejecting.sign(template))
        val mismatching = object : EventTemplateSigner {
            override val pubkey = signer.pubkey
            override suspend fun sign(draft: EventDraft): CryptoResult<Event> =
                signer.sign(draft.copy(content = "another event"))
        }
        assertIs<CryptoResult.Err>(mismatching.sign(template))
        assertIs<CryptoResult.Err>(signer.sign(eventTemplate(EventKind(1), "\u0000")))
        assertIs<CryptoResult.Err>(signer.sign(eventTemplate(EventKind(1), "\uD800")))
        assertIs<CryptoResult.Ok<Unit>>(verifier.verify(
            signer.sign(eventTemplate(EventKind(1), "Unicode 🦄")).getOrThrow().event,
        ))
    }

    @Test
    fun cancellationOfRemoteSigningPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        val remote = object : EventTemplateSigner {
            override val pubkey = signer.pubkey
            override suspend fun sign(draft: EventDraft): CryptoResult<Event> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        var returnedResult = false
        val job = launch {
            remote.sign(eventTemplate(EventKind(1), "hello"))
            returnedResult = true
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(returnedResult)
    }

    @Test
    fun secretRepresentationsAndSuccessfulResultsAreRedacted() {
        val pair = KeyPair(secret, signer.pubkey)
        for (description in listOf(secret.toString(), pair.toString(), signer.toString(),
            CryptoResult.Ok(secret.bytes).toString(), CryptoResult.Ok("private plaintext").toString())) {
            assertFalse(description.contains(secret.hex))
            assertFalse(description.contains("private plaintext"))
        }
    }

    @Test
    fun invalidPrivateScalarsCannotCreateSigners() {
        for (hex in listOf("00".repeat(32), "ff".repeat(32))) {
            val result = NostrSigner.fromSecretKey(SecretKey.fromHex(hex))
            assertIs<CryptoResult.Err>(result)
            assertFalse(result.toString().contains(hex))
        }
    }
}
