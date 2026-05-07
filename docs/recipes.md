# Recipes

These examples assume the standard shared services:

```kotlin
import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.crypto.NostrCrypto

val codec = KotlinxNostrJsonCodec()
val ctx = SimpleNipModuleContext(codec, NostrCrypto)

private fun <T> requireOk(result: CryptoResult<T>): T = when (result) {
    is CryptoResult.Ok -> result.value
    is CryptoResult.Err -> error("Crypto operation failed: ${result.error}")
}
```

Examples that call `await...`, `publish`, `subscribe`, or `closeAndJoin` run
from a suspending scope.

## Sign a Text Note

```kotlin
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.crypto.NostrSigner

val signer = requireOk(NostrSigner.generate())

val note = signer.sign(
    eventTemplate(
        kind = EventKind(1),
        content = "Hello, Nostr!",
    ),
)

println(note.event.id.hex)
```

## Restore a Signer From a Secret Key

```kotlin
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.crypto.NostrSigner

val secretKey = SecretKey.fromHex("0000000000000000000000000000000000000000000000000000000000000001")
val signer = requireOk(NostrSigner.fromSecretKey(secretKey))
```

## Decode and Upgrade a Relay Message

```kotlin
import io.github.nicolals.nostr.core.nip.NipRegistry
import io.github.nicolals.nostr.nip01.Nip01Module
import io.github.nicolals.nostr.nip01.message.NoticeMessage

val registry = NipRegistry.Builder(codec)
    .install(Nip01Module(ctx))
    .build()

val raw = codec.decodeRelayMessage("""["NOTICE","relay is restarting"]""")

when (val message = registry.messages.upgradeRelay(raw)) {
    is NoticeMessage -> println(message.message)
    else -> println("Unhandled relay message: ${message.raw.tokenOrNull}")
}
```

## Create a Metadata Event

```kotlin
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip01.event.MetadataEvent
import io.github.nicolals.nostr.nip01.model.Metadata

val metadata = Metadata(
    name = "alice",
    about = "Kotlin Nostr client",
    picture = "https://example.com/alice.png",
    displayName = "Alice",
)

val metadataEvent = signer.sign(
    MetadataEvent.template(
        metadata = metadata,
        createdAt = UnixTimeSeconds.now(),
        tags = emptyList(),
        ctx = ctx,
    ),
)
```

Install `Nip01Module(ctx)` and call `registry.events.upgrade(event)` to turn
incoming kind `0` events into `MetadataEvent` when the content is valid.

## Publish and Collect Events

```kotlin
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val session = CoroutinesRelaySession(
    url = RelayUrl.parse("wss://relay.example.com"),
    moduleContext = ctx,
    transportFactory = KtorRelayTransportFactory(),
    scope = scope,
)

session.connect()

val sub = session.subscribe(
    filters = listOf(Filter(kinds = listOf(EventKind(1)), limit = 10)),
)

val job = scope.launch {
    sub.events.collect { event ->
        println(event.content)
    }
}

if (session.awaitConnected(timeoutMs = 10_000)) {
    session.publish(note.event).await(timeoutMs = 10_000)
}
sub.close()
job.cancel()
session.closeAndJoin()
```

## Encrypt a NIP-04 Direct Message

```kotlin
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip04.event.EncryptedDirectMessageEvent

val recipientSigner = requireOk(NostrSigner.generate())

val dmTemplate = requireOk(
    EncryptedDirectMessageEvent.template(
        recipientPubkey = recipientSigner.pubkey,
        plaintext = "secret message",
        createdAt = UnixTimeSeconds.now(),
        ctx = ctx,
        senderPrivateKey = signer.keys.secretKey.bytes,
    ),
)

val dm = signer.sign(dmTemplate)
val plaintext = requireOk(dm.decryptForRecipient(recipientSigner.keys.secretKey.bytes, ctx))
```

## Encrypt and Decrypt NIP-44 Content

```kotlin
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip44.decryptNip44Content
import io.github.nicolals.nostr.nip44.encryptNip44Content

val recipientSigner = requireOk(NostrSigner.generate())

val encrypted = requireOk(
    ctx.encryptNip44Content(
        plaintext = "private payload",
        senderPrivateKey = signer.keys.secretKey.bytes,
        recipientPublicKey = recipientSigner.pubkey,
    ),
)

val decrypted = requireOk(
    ctx.decryptNip44Content(
        content = encrypted,
        receiverPrivateKey = recipientSigner.keys.secretKey.bytes,
        senderPublicKey = signer.pubkey,
    ),
)
```

For production message flows, validate the outer event signature and expected
pubkey before decrypting event content.

## Create a NIP-45 COUNT Request

```kotlin
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.nip45.message.CountClientMessage

val countMessage = CountClientMessage.create(
    subscriptionId = SubscriptionId("count-1"),
    filters = listOf(Filter(kinds = listOf(EventKind(1)))),
    codec = codec,
)

val json = codec.encodeClientMessageString(countMessage)
```

Send `json` through a `RelayConnection` or a runtime extension that exposes
custom client messages.

## Create and Track a Nostr Wallet Connect Request

```kotlin
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.core.runtime.PublishStatus
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.nip47.NwcEncryptionSelector
import io.github.nicolals.nostr.nip47.event.NwcRequestEvent
import io.github.nicolals.nostr.nip47.event.NwcResponseEvent
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcGetBalanceRequest
import io.github.nicolals.nostr.nip47.model.NwcResponse
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

suspend fun requestWalletBalance(
    session: CoroutinesRelaySession,
    clientSigner: NostrSigner,
    walletPubkey: PublicKey,
    encryption: NwcEncryption = NwcEncryption.NIP04,
): NwcResponse {
    val responseHandle = session.subscribe(
        listOf(
            Filter(
                kinds = listOf(NwcResponseEvent.KIND),
                authors = listOf(walletPubkey),
                tagFilters = mapOf("p" to listOf(clientSigner.pubkey.hex)),
            ),
        ),
    )

    val requestTemplate = requireOk(
        NwcRequestEvent.template(
            walletPubkey = walletPubkey,
            request = NwcGetBalanceRequest,
            createdAt = UnixTimeSeconds.now(),
            ctx = ctx,
            clientPrivateKey = clientSigner.keys.secretKey.bytes,
            encryption = encryption,
        ),
    )

    val requestEvent = clientSigner.sign(requestTemplate)
    val requestId = requestEvent.event.id.hex

    when (val status = session.publish(requestEvent.event).await(timeoutMs = 5_000)) {
        is PublishStatus.Accepted -> Unit
        else -> error("NWC request was not accepted by the relay: $status")
    }

    try {
        return responseHandle.events
            .mapNotNull { event ->
                val responseEvent = NwcResponseEvent.fromEventOrNull(
                    event,
                    NwcEncryptionSelector { _ -> encryption },
                ) ?: return@mapNotNull null

                if (responseEvent.requestEventId?.hex != requestId) {
                    return@mapNotNull null
                }

                responseEvent.decryptForClient(
                    ctx = ctx,
                    clientPrivateKey = clientSigner.keys.secretKey.bytes,
                ).getOrNull()
            }
            .first()
    } finally {
        responseHandle.close()
    }
}
```

This mirrors the important low-level flow used by `nwc-kmp`: subscribe for
response events before publishing the request, filter responses by wallet
pubkey and client `p` tag, then correlate by the response `e` tag. Production
clients should also handle wallets that omit the response `e` tag when exactly
one request is pending.
