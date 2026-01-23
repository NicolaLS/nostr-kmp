package io.github.nicolals.nostr.crypto.benchmarks

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.event.view.GenericEvent
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.crypto.NostrSigning
import io.github.nicolals.nostr.nip04.decryptNip04
import io.github.nicolals.nostr.nip04.decryptNip04Content
import io.github.nicolals.nostr.nip04.encryptNip04
import io.github.nicolals.nostr.nip04.encryptNip04Content
import io.github.nicolals.nostr.nip44.decryptNip44
import io.github.nicolals.nostr.nip44.encryptNip44
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.random.Random

internal object NostrPayloadSizes {
    const val TINY_NOTE = 32
    const val SHORT_NOTE = 280
    const val MEDIUM_NOTE = 1024
    const val LONG_NOTE = 4096
    const val ARTICLE = 16384

    const val NWC_REQUEST = 256
    const val NWC_RESPONSE = 512

    const val SHORT_DM = 128
    const val MEDIUM_DM = 512
    const val LONG_DM = 2048

    const val EVENTS_PER_FEED_PAGE = 20
    const val EVENTS_PER_PROFILE_LOAD = 50
}

internal object BenchmarkSuite {
    fun scenarios(ctx: NipModuleContext, batchScale: Int): List<BenchmarkScenario> {
        val scale = if (batchScale > 0) batchScale else 1
        val tinyBatch = scaleBatch(200, scale)
        val smallBatch = scaleBatch(100, scale)
        val mediumBatch = scaleBatch(50, scale)
        val largeBatch = scaleBatch(20, scale)
        val heavyBatch = scaleBatch(10, scale)

        return listOf(
            HarnessOverheadBenchmark(tinyBatch),

            Sha256Benchmark(NostrPayloadSizes.TINY_NOTE, smallBatch),
            Sha256Benchmark(NostrPayloadSizes.SHORT_NOTE, smallBatch),
            Sha256Benchmark(NostrPayloadSizes.MEDIUM_NOTE, mediumBatch),
            Sha256Benchmark(NostrPayloadSizes.LONG_NOTE, largeBatch),

            RandomBytesBenchmark(32, smallBatch),
            RandomBytesBenchmark(256, smallBatch),

            Base64EncodeBenchmark(32, smallBatch),
            Base64EncodeBenchmark(512, smallBatch),
            Base64EncodeBenchmark(4096, largeBatch),

            Base64DecodeBenchmark(32, smallBatch),
            Base64DecodeBenchmark(512, smallBatch),
            Base64DecodeBenchmark(4096, largeBatch),

            Chacha20Benchmark(NostrPayloadSizes.SHORT_DM, smallBatch),
            Chacha20Benchmark(NostrPayloadSizes.MEDIUM_DM, smallBatch),
            Chacha20Benchmark(NostrPayloadSizes.LONG_DM, mediumBatch),

            EventCreationBenchmark(NostrPayloadSizes.TINY_NOTE, mediumBatch),
            EventCreationBenchmark(NostrPayloadSizes.SHORT_NOTE, mediumBatch),
            EventCreationBenchmark(NostrPayloadSizes.MEDIUM_NOTE, mediumBatch),

            EventVerificationBenchmark(NostrPayloadSizes.TINY_NOTE, mediumBatch),
            EventVerificationBenchmark(NostrPayloadSizes.SHORT_NOTE, mediumBatch),
            EventVerificationBenchmark(NostrPayloadSizes.MEDIUM_NOTE, mediumBatch),

            Nip04EncryptBenchmark(ctx, NostrPayloadSizes.SHORT_DM, mediumBatch),
            Nip04EncryptBenchmark(ctx, NostrPayloadSizes.MEDIUM_DM, mediumBatch),
            Nip04EncryptBenchmark(ctx, NostrPayloadSizes.LONG_DM, largeBatch),

            Nip04DecryptBenchmark(ctx, NostrPayloadSizes.SHORT_DM, mediumBatch),
            Nip04DecryptBenchmark(ctx, NostrPayloadSizes.MEDIUM_DM, mediumBatch),
            Nip04DecryptBenchmark(ctx, NostrPayloadSizes.LONG_DM, largeBatch),

            Nip44EncryptBenchmark(ctx, NostrPayloadSizes.SHORT_DM, largeBatch),
            Nip44EncryptBenchmark(ctx, NostrPayloadSizes.MEDIUM_DM, largeBatch),
            Nip44EncryptBenchmark(ctx, NostrPayloadSizes.LONG_DM, heavyBatch),

            Nip44DecryptBenchmark(ctx, NostrPayloadSizes.SHORT_DM, largeBatch),
            Nip44DecryptBenchmark(ctx, NostrPayloadSizes.MEDIUM_DM, largeBatch),
            Nip44DecryptBenchmark(ctx, NostrPayloadSizes.LONG_DM, heavyBatch),

            FeedLoadSimulationBenchmark(NostrPayloadSizes.EVENTS_PER_FEED_PAGE, pages = scaleBatch(5, scale)),
            FeedLoadSimulationBenchmark(NostrPayloadSizes.EVENTS_PER_PROFILE_LOAD, pages = scaleBatch(3, scale)),

            Nip04RoundTripBenchmark(ctx, NostrPayloadSizes.MEDIUM_DM, largeBatch),
            Nip44RoundTripBenchmark(ctx, NostrPayloadSizes.MEDIUM_DM, heavyBatch),

            NwcWorkflowBenchmark(ctx, workflowsPerRun = scaleBatch(10, scale)),
        )
    }
}

internal class Sha256Benchmark(
    private val payloadSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "SHA256-${payloadSize}B"
    override val description = "Hash $payloadSize bytes with SHA256"
    override val operationsPerRun: Int = batchSize

    private lateinit var data: ByteString

    override fun setup() {
        data = Random.nextBytes(payloadSize).toByteString()
    }

    override fun run() {
        repeat(batchSize) {
            val result = NostrCrypto.sha256(data).requireOk()
            BenchmarkSink.consume(result)
        }
    }
}

internal class RandomBytesBenchmark(
    private val length: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "RandomBytes-${length}B"
    override val description = "Generate $length random bytes"
    override val operationsPerRun: Int = batchSize

    override fun run() {
        repeat(batchSize) {
            val result = NostrCrypto.randomBytes(length).requireOk()
            BenchmarkSink.consume(result)
        }
    }
}

internal class Base64EncodeBenchmark(
    private val payloadSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "Base64-Encode-${payloadSize}B"
    override val description = "Base64 encode $payloadSize bytes"
    override val operationsPerRun: Int = batchSize

    private lateinit var data: ByteString

    override fun setup() {
        data = Random.nextBytes(payloadSize).toByteString()
    }

    override fun run() {
        repeat(batchSize) {
            val result = NostrCrypto.base64Encode(data).requireOk()
            BenchmarkSink.consume(result)
        }
    }
}

internal class Base64DecodeBenchmark(
    private val payloadSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "Base64-Decode-${payloadSize}B"
    override val description = "Base64 decode $payloadSize bytes"
    override val operationsPerRun: Int = batchSize

    private lateinit var encoded: String

    override fun setup() {
        val data = Random.nextBytes(payloadSize).toByteString()
        encoded = NostrCrypto.base64Encode(data).requireOk()
    }

    override fun run() {
        repeat(batchSize) {
            val result = NostrCrypto.base64Decode(encoded).requireOk()
            BenchmarkSink.consume(result)
        }
    }
}

internal class Chacha20Benchmark(
    private val payloadSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "Chacha20-${payloadSize}B"
    override val description = "Chacha20-IETF XOR $payloadSize bytes"
    override val operationsPerRun: Int = batchSize

    private lateinit var key: ByteString
    private lateinit var nonce: ByteString
    private lateinit var input: ByteString

    override fun setup() {
        key = Random.nextBytes(32).toByteString()
        nonce = Random.nextBytes(12).toByteString()
        input = Random.nextBytes(payloadSize).toByteString()
    }

    override fun run() {
        repeat(batchSize) {
            val result = NostrCrypto.chacha20IetfXor(key, nonce, input).requireOk()
            BenchmarkSink.consume(result)
        }
    }
}

internal class EventCreationBenchmark(
    private val contentSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "EventCreate-${contentSize}B"
    override val description = "Create and sign event with $contentSize byte content"
    override val operationsPerRun: Int = batchSize

    private lateinit var signer: NostrSigner
    private lateinit var template: EventTemplate<GenericEvent>

    override fun setup() {
        signer = NostrSigner.generate().requireOk()
        template = eventTemplate(
            kind = EventKind(1),
            content = "x".repeat(contentSize),
            tags = emptyList(),
            createdAt = UnixTimeSeconds(0),
        )
    }

    override fun run() {
        repeat(batchSize) {
            val event = signer.sign(template).event
            BenchmarkSink.consume(event.id.bytes)
        }
    }
}

internal class EventVerificationBenchmark(
    private val contentSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "EventVerify-${contentSize}B"
    override val description = "Verify event signature with $contentSize byte content"
    override val operationsPerRun: Int = batchSize

    private lateinit var event: Event

    override fun setup() {
        val signer = NostrSigner.generate().requireOk()
        val template = eventTemplate(
            kind = EventKind(1),
            content = "x".repeat(contentSize),
            tags = emptyList(),
            createdAt = UnixTimeSeconds(0),
        )
        event = signer.sign(template).event
    }

    override fun run() {
        repeat(batchSize) {
            val valid = NostrSigning.schnorrVerify(event.sig.bytes, event.id.bytes, event.pubkey.bytes).requireOk()
            BenchmarkSink.consume(valid)
            check(valid)
        }
    }
}

internal class Nip04EncryptBenchmark(
    private val ctx: NipModuleContext,
    private val messageSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "NIP04-Encrypt-${messageSize}B"
    override val description = "NIP-04 encrypt $messageSize byte message"
    override val operationsPerRun: Int = batchSize

    private lateinit var senderPrivateKey: ByteString
    private var receiverPublicKey: PublicKey? = null
    private lateinit var message: String

    override fun setup() {
        senderPrivateKey = generateSecretKey()
        val receiverPrivateKey = generateSecretKey()
        receiverPublicKey = derivePublicKey(receiverPrivateKey)
        message = "x".repeat(messageSize)
    }

    override fun run() {
        val pubKey = requireNotNull(receiverPublicKey)
        repeat(batchSize) {
            val result = ctx.encryptNip04(message, senderPrivateKey, pubKey).requireOk()
            BenchmarkSink.consume(result.cipherTextBase64)
        }
    }
}

internal class Nip04DecryptBenchmark(
    private val ctx: NipModuleContext,
    private val messageSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "NIP04-Decrypt-${messageSize}B"
    override val description = "NIP-04 decrypt $messageSize byte message"
    override val operationsPerRun: Int = batchSize

    private lateinit var receiverPrivateKey: ByteString
    private var senderPublicKey: PublicKey? = null
    private lateinit var ciphertext: io.github.nicolals.nostr.nip04.model.Nip04CipherText

    override fun setup() {
        val senderPrivateKey = generateSecretKey()
        receiverPrivateKey = generateSecretKey()
        senderPublicKey = derivePublicKey(senderPrivateKey)
        val receiverPublicKey = derivePublicKey(receiverPrivateKey)
        val message = "x".repeat(messageSize)
        ciphertext = ctx.encryptNip04(message, senderPrivateKey, receiverPublicKey).requireOk()
    }

    override fun run() {
        val pubKey = requireNotNull(senderPublicKey)
        repeat(batchSize) {
            val result = ctx.decryptNip04(ciphertext, receiverPrivateKey, pubKey).requireOk()
            BenchmarkSink.consume(result)
        }
    }
}

internal class Nip44EncryptBenchmark(
    private val ctx: NipModuleContext,
    private val messageSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "NIP44-Encrypt-${messageSize}B"
    override val description = "NIP-44 encrypt $messageSize byte message"
    override val operationsPerRun: Int = batchSize

    private lateinit var senderPrivateKey: ByteString
    private var receiverPublicKey: PublicKey? = null
    private lateinit var message: String

    override fun setup() {
        senderPrivateKey = generateSecretKey()
        val receiverPrivateKey = generateSecretKey()
        receiverPublicKey = derivePublicKey(receiverPrivateKey)
        message = "x".repeat(messageSize)
    }

    override fun run() {
        val pubKey = requireNotNull(receiverPublicKey)
        repeat(batchSize) {
            val result = ctx.encryptNip44(message, senderPrivateKey, pubKey).requireOk()
            BenchmarkSink.consume(result.ciphertext)
        }
    }
}

internal class Nip44DecryptBenchmark(
    private val ctx: NipModuleContext,
    private val messageSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "NIP44-Decrypt-${messageSize}B"
    override val description = "NIP-44 decrypt $messageSize byte message"
    override val operationsPerRun: Int = batchSize

    private lateinit var receiverPrivateKey: ByteString
    private var senderPublicKey: PublicKey? = null
    private lateinit var payload: io.github.nicolals.nostr.nip44.model.Nip44Payload

    override fun setup() {
        val senderPrivateKey = generateSecretKey()
        receiverPrivateKey = generateSecretKey()
        senderPublicKey = derivePublicKey(senderPrivateKey)
        val receiverPublicKey = derivePublicKey(receiverPrivateKey)
        val message = "x".repeat(messageSize)
        payload = ctx.encryptNip44(message, senderPrivateKey, receiverPublicKey).requireOk()
    }

    override fun run() {
        val pubKey = requireNotNull(senderPublicKey)
        repeat(batchSize) {
            val result = ctx.decryptNip44(payload, receiverPrivateKey, pubKey).requireOk()
            BenchmarkSink.consume(result)
        }
    }
}

internal class FeedLoadSimulationBenchmark(
    private val eventCount: Int,
    private val pages: Int = 1,
) : BenchmarkScenario {
    override val name = if (pages == 1) {
        "FeedLoad-${eventCount}events"
    } else {
        "FeedLoad-${eventCount}events x$pages"
    }
    override val description = "Simulate loading and verifying $eventCount events across $pages pages"
    override val operationsPerRun: Int = eventCount * pages

    private lateinit var events: List<Event>

    override fun setup() {
        events = (1..eventCount).map { index ->
            val signer = NostrSigner.generate().requireOk()
            val template = eventTemplate(
                kind = EventKind(1),
                content = "Event #$index content: " + "x".repeat(200),
                tags = emptyList(),
                createdAt = UnixTimeSeconds(0),
            )
            signer.sign(template).event
        }
    }

    override fun run() {
        repeat(pages) {
            events.forEach { event ->
                val valid = NostrSigning.schnorrVerify(event.sig.bytes, event.id.bytes, event.pubkey.bytes).requireOk()
                BenchmarkSink.consume(valid)
                check(valid)
            }
        }
    }
}

internal class NwcWorkflowBenchmark(
    private val ctx: NipModuleContext,
    private val workflowsPerRun: Int,
) : BenchmarkScenario {
    override val name = if (workflowsPerRun == 1) "NWC-Workflow" else "NWC-Workflow x$workflowsPerRun"
    override val description = "NIP-47 request/response using NIP-04 encryption and event signing"
    override val operationsPerRun: Int = workflowsPerRun

    private lateinit var clientSigner: NostrSigner
    private lateinit var walletSigner: NostrSigner
    private lateinit var clientSecretKey: ByteString
    private lateinit var walletSecretKey: ByteString
    private var clientPublicKey: PublicKey? = null
    private var walletPublicKey: PublicKey? = null

    private val request = """{"method":"pay_invoice","params":{"invoice":"lnbc..."}}"""
    private val response = """{"result_type":"pay_invoice","result":{"preimage":"abc123"}}"""

    override fun setup() {
        clientSigner = NostrSigner.generate().requireOk()
        walletSigner = NostrSigner.generate().requireOk()
        clientSecretKey = clientSigner.keys.secretKey.bytes
        walletSecretKey = walletSigner.keys.secretKey.bytes
        clientPublicKey = clientSigner.pubkey
        walletPublicKey = walletSigner.pubkey
    }

    override fun run() {
        val walletPubKey = requireNotNull(walletPublicKey)
        val clientPubKey = requireNotNull(clientPublicKey)
        repeat(workflowsPerRun) {
            val encryptedRequest = ctx.encryptNip04Content(request, clientSecretKey, walletPubKey).requireOk()
            val requestTags: EventTags = listOf(listOf("p", walletPubKey.hex))
            val requestEvent = clientSigner.sign(
                EventTemplate(
                    draft = EventDraft(
                        createdAt = UnixTimeSeconds(0),
                        kind = EventKind(23194),
                        tags = requestTags,
                        content = encryptedRequest,
                    ),
                    wrap = ::GenericEvent,
                ),
            ).event

            val requestValid = NostrSigning.schnorrVerify(
                requestEvent.sig.bytes,
                requestEvent.id.bytes,
                requestEvent.pubkey.bytes,
            ).requireOk()
            BenchmarkSink.consume(requestValid)
            check(requestValid)

            val decryptedRequest = ctx.decryptNip04Content(requestEvent.content, walletSecretKey, clientPubKey).requireOk()
            BenchmarkSink.consume(decryptedRequest)

            val encryptedResponse = ctx.encryptNip04Content(response, walletSecretKey, clientPubKey).requireOk()
            val responseTags: EventTags = listOf(
                listOf("e", requestEvent.id.hex),
                listOf("p", clientPubKey.hex),
            )
            val responseEvent = walletSigner.sign(
                EventTemplate(
                    draft = EventDraft(
                        createdAt = UnixTimeSeconds(0),
                        kind = EventKind(23195),
                        tags = responseTags,
                        content = encryptedResponse,
                    ),
                    wrap = ::GenericEvent,
                ),
            ).event

            val responseValid = NostrSigning.schnorrVerify(
                responseEvent.sig.bytes,
                responseEvent.id.bytes,
                responseEvent.pubkey.bytes,
            ).requireOk()
            BenchmarkSink.consume(responseValid)
            check(responseValid)

            val decryptedResponse = ctx.decryptNip04Content(responseEvent.content, clientSecretKey, walletPubKey).requireOk()
            BenchmarkSink.consume(decryptedResponse)
        }
    }
}

internal class Nip04RoundTripBenchmark(
    private val ctx: NipModuleContext,
    private val messageSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "NIP04-RoundTrip-${messageSize}B"
    override val description = "NIP-04 encrypt + decrypt $messageSize byte message"
    override val operationsPerRun: Int = batchSize

    private lateinit var senderPrivateKey: ByteString
    private lateinit var receiverPrivateKey: ByteString
    private var senderPublicKey: PublicKey? = null
    private var receiverPublicKey: PublicKey? = null
    private lateinit var message: String

    override fun setup() {
        senderPrivateKey = generateSecretKey()
        receiverPrivateKey = generateSecretKey()
        senderPublicKey = derivePublicKey(senderPrivateKey)
        receiverPublicKey = derivePublicKey(receiverPrivateKey)
        message = "x".repeat(messageSize)
    }

    override fun run() {
        val senderPubKey = requireNotNull(senderPublicKey)
        val receiverPubKey = requireNotNull(receiverPublicKey)
        repeat(batchSize) {
            val ciphertext = ctx.encryptNip04(message, senderPrivateKey, receiverPubKey).requireOk()
            val plaintext = ctx.decryptNip04(ciphertext, receiverPrivateKey, senderPubKey).requireOk()
            BenchmarkSink.consume(plaintext)
        }
    }
}

internal class Nip44RoundTripBenchmark(
    private val ctx: NipModuleContext,
    private val messageSize: Int,
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "NIP44-RoundTrip-${messageSize}B"
    override val description = "NIP-44 encrypt + decrypt $messageSize byte message"
    override val operationsPerRun: Int = batchSize

    private lateinit var senderPrivateKey: ByteString
    private lateinit var receiverPrivateKey: ByteString
    private var senderPublicKey: PublicKey? = null
    private var receiverPublicKey: PublicKey? = null
    private lateinit var message: String

    override fun setup() {
        senderPrivateKey = generateSecretKey()
        receiverPrivateKey = generateSecretKey()
        senderPublicKey = derivePublicKey(senderPrivateKey)
        receiverPublicKey = derivePublicKey(receiverPrivateKey)
        message = "x".repeat(messageSize)
    }

    override fun run() {
        val senderPubKey = requireNotNull(senderPublicKey)
        val receiverPubKey = requireNotNull(receiverPublicKey)
        repeat(batchSize) {
            val payload = ctx.encryptNip44(message, senderPrivateKey, receiverPubKey).requireOk()
            val plaintext = ctx.decryptNip44(payload, receiverPrivateKey, senderPubKey).requireOk()
            BenchmarkSink.consume(plaintext)
        }
    }
}

internal class HarnessOverheadBenchmark(
    private val batchSize: Int,
) : BenchmarkScenario {
    override val name = "Harness-Overhead"
    override val description = "Loop overhead baseline (no crypto)"
    override val operationsPerRun: Int = batchSize

    private var counter: Int = 0

    override fun run() {
        var value = counter
        repeat(batchSize) { value += it }
        counter = value
        BenchmarkSink.consume(value)
    }
}

internal inline fun <T> CryptoResult<T>.requireOk(): T = when (this) {
    is CryptoResult.Ok -> value
    is CryptoResult.Err -> error("Crypto error: ${error}")
}

private fun generateSecretKey(): ByteString = NostrSigning.generateSecretKey().requireOk()

private fun derivePublicKey(secretKey: ByteString): PublicKey =
    PublicKey.fromBytes(NostrSigning.derivePublicKeyXOnly(secretKey).requireOk())

private fun scaleBatch(base: Int, scale: Int): Int = (base * scale).coerceAtLeast(1)

internal object BenchmarkSink {
    @Volatile private var bytes: ByteString? = null
    @Volatile private var text: String? = null
    @Volatile private var flag: Boolean = false
    @Volatile private var number: Int = 0

    fun consume(value: ByteString) {
        bytes = value
    }

    fun consume(value: String) {
        text = value
    }

    fun consume(value: Boolean) {
        flag = value
    }

    fun consume(value: Int) {
        number = value
    }
}
