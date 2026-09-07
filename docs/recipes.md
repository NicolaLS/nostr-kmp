# Recipes

Examples run inside suspending functions or coroutine scopes. The shared values
below are explicit dependencies; no module context is required.

```kotlin
import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.codec.NostrJsonCodec
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrEventVerifier
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession

val codec = KotlinxNostrJsonCodec()
val verifier = NostrEventVerifier()
val signer = NostrSigner.generate().getOrThrow()
```

## Sign a typed metadata event

```kotlin
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip01.event.MetadataEvent
import io.github.nicolals.nostr.nip01.model.Metadata

val metadata = Metadata(name = "alice", about = "Kotlin client", picture = "https://example.com/avatar.png")
val signed = signer.sign(
    MetadataEvent.template(
        metadata = metadata,
        createdAt = UnixTimeSeconds.now(),
        tags = emptyList(),
        codec = codec,
    ),
).getOrThrow()
```

## Decode and upgrade messages

```kotlin
import io.github.nicolals.nostr.core.nip.NipRegistry
import io.github.nicolals.nostr.nip01.Nip01Module
import io.github.nicolals.nostr.nip01.message.NoticeMessage

val registry = NipRegistry.Builder(codec).install(Nip01Module(codec)).build()
val raw = codec.decodeRelayMessage("""["NOTICE","relay restarting"]""")
val message = registry.messages.upgradeRelay(raw)
if (message is NoticeMessage) println(message.message)
```

Decoding and upgrading do not verify an event. For events obtained outside a relay
session, call `verifier.verify(event)` before using them as authenticated input.

## COUNT through a session

Install `Nip45Module(codec)` when constructing the session. Use a distinct query
ID for each outstanding count. Start the response collector before sending.

```kotlin
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.core.transport.TransportResult
import io.github.nicolals.nostr.nip45.message.CountClientMessage
import io.github.nicolals.nostr.nip45.message.CountRelayMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

suspend fun countNotes(session: CoroutinesRelaySession, codec: NostrJsonCodec): Long = coroutineScope {
    val id = SubscriptionId("count-notes")
    val response = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(5_000) {
            session.messages.filterIsInstance<CountRelayMessage>().first { it.subscriptionId == id }
        }
    }
    try {
        val sent = session.send(CountClientMessage.create(id, listOf(Filter()), codec))
        check(sent is TransportResult.Success) { "COUNT could not be queued for transport" }
        response.await().result.count
    } finally {
        response.cancel()
    }
}
```

A successful `send` means the transport accepted the message for sending, not that
the relay has processed it. The matching response completes the operation.

## Authenticate with a selected relay

Install `Nip42Module(codec)`. Authentication identifies your account to the relay;
choose the relay/account in application policy before enabling this handler. The
SDK does not automatically sign every relay challenge.

```kotlin
import io.github.nicolals.nostr.nip42.event.AuthEvent
import io.github.nicolals.nostr.nip42.protocol.Nip42Intent
import io.github.nicolals.nostr.nip42.protocol.Nip42Signal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

val authObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    session.signals.collect { signal ->
        when (signal) {
            is Nip42Signal.ChallengeReceived -> {
                val authEvent = signer.sign(
                    AuthEvent.template(
                        relayUrl = session.url.value,
                        challenge = signal.challenge,
                        createdAt = UnixTimeSeconds.now(),
                    ),
                ).getOrThrow()
                session.submit(Nip42Intent.Authenticate(authEvent.event))
            }
            is Nip42Signal.Authenticated -> println("Relay authentication succeeded")
            is Nip42Signal.AuthFailed -> println("Relay authentication rejected")
            else -> Unit
        }
    }
}
session.connect()
```

Application policy can retry a subscription after successful authentication.
Cancel `authObserver` with the owning feature. Handle signer rejection and observer
overflow in production code; neither is silently converted into success.

## NIP-44 with local or remote encryption

```kotlin
import io.github.nicolals.nostr.core.primitives.SecretKey
import io.github.nicolals.nostr.nip44.LocalNip44Cipher
import io.github.nicolals.nostr.nip44.Nip44Cipher

val senderCipher: Nip44Cipher = LocalNip44Cipher(senderSecretKey, NostrCrypto)
val recipientCipher: Nip44Cipher = LocalNip44Cipher(recipientSecretKey, NostrCrypto)
val content = senderCipher.encrypt("private payload", recipientPubkey).getOrThrow()
val plaintext = recipientCipher.decrypt(content, senderPubkey).getOrThrow()
```

`senderSecretKey` and `recipientSecretKey` are `SecretKey` values loaded by the
application. A remote implementation of `Nip44Cipher` can suspend for I/O or user
approval without exporting those keys. Keep plaintext and key material out of logs.
For Nostr event workflows, verify the outer event and expected identities as well.

## NIP-04 compatibility

```kotlin
import io.github.nicolals.nostr.nip04.LocalNip04Cipher
import io.github.nicolals.nostr.nip04.event.EncryptedDirectMessageEvent

val template = EncryptedDirectMessageEvent.template(
    recipientPubkey = recipientPubkey,
    plaintext = "private payload",
    createdAt = UnixTimeSeconds.now(),
    cipher = LocalNip04Cipher(senderSecretKey, NostrCrypto),
).getOrThrow()
val message = signer.sign(template).getOrThrow()
val plaintext = message.decryptForRecipient(
    cipher = LocalNip04Cipher(recipientSecretKey, NostrCrypto),
    verifier = verifier,
).getOrThrow()
```

Use the signer corresponding to `senderSecretKey`. The event helper verifies the
signature before decrypting. Select NIP-44 for new protocols where it is supported;
NIP-04 remains available for existing protocol interoperability.

## Low-level NWC request construction

The higher-level `nwc-kmp` client manages response validation, correlation, timeouts,
and connection ownership. When building another client, compose the same capabilities:

```kotlin
import io.github.nicolals.nostr.nip47.Nip47Cipher
import io.github.nicolals.nostr.nip47.event.NwcRequestEvent
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.model.NwcGetBalanceRequest

val cipher = Nip47Cipher(
    nip04 = LocalNip04Cipher(clientSecretKey, NostrCrypto),
    nip44 = LocalNip44Cipher(clientSecretKey, NostrCrypto),
)
val template = NwcRequestEvent.template(
    walletPubkey = walletPubkey,
    request = NwcGetBalanceRequest,
    createdAt = UnixTimeSeconds.now(),
    codec = codec,
    cipher = cipher,
    encryption = NwcEncryption.NIP44_V2,
).getOrThrow()
val request = clientSigner.sign(template).getOrThrow()
```

Choose encryption from authenticated wallet information. Collect responses before
publishing. Verify response IDs/signatures, wallet author, client recipient, method,
and exact request ID before accepting a result. An encrypted payload alone does not
establish that its sender is the configured wallet.
