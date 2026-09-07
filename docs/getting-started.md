# Getting Started

The same APIs work from common Kotlin on JVM, Android, and iOS. Signing,
connection waits, custom protocol commands, and shutdown are suspending operations.

## Dependencies

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nicolals:nostr-codec-kotlinx:0.3.3-SNAPSHOT")
            implementation("io.github.nicolals:nostr-crypto:0.3.3-SNAPSHOT")
            implementation("io.github.nicolals:nostr-transport-ktor:0.3.3-SNAPSHOT")
            implementation("io.github.nicolals:nostr-runtime-coroutines:0.3.3-SNAPSHOT")
        }
    }
}
```

Optional NIP artifacts are added independently. For wallet operations, use
[`nwc-kmp`](../nwc-kmp/README.md), which supplies its own default implementations.

## Signing

```kotlin
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.crypto.NostrSigner

val signer = NostrSigner.generate().getOrThrow()
val note = signer.sign(eventTemplate(EventKind(1), "Hello, Nostr!")).getOrThrow()
```

`sign` returns `CryptoResult<T>`. Handle `CryptoResult.Err` when your application
needs to distinguish failures; `getOrThrow()` is useful in short examples.
Cancellation propagates normally. Restore a local signer using
`NostrSigner.fromSecretKey(SecretKey.fromHex(...))`. A signer exposes its public
key and signing operation; it does not expose its private key.

Drafts and signed events snapshot nested tag lists. Mutating the lists used to
create a draft cannot alter the signed event.

## One relay

```kotlin
import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.crypto.NostrEventVerifier
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory

val codec = KotlinxNostrJsonCodec()
val transport = KtorRelayTransportFactory()
val session = CoroutinesRelaySession(
    url = RelayUrl.parse("wss://relay.example.com"),
    codec = codec,
    eventVerifier = NostrEventVerifier(),
    transportFactory = transport,
    scope = scope,
)
session.connect()
val connected = session.awaitConnected(timeoutMs = 10_000)
```

`scope` should belong to the feature or service using the session. Incoming events
must pass the supplied verifier before reaching observers, subscriptions, or the
deduplication cache. `NostrEventVerifier` checks the canonical event ID and Schnorr
signature. A codec alone only parses structural types; parsing does not authenticate
an event.

## Publishing and subscribing

```kotlin
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.EventKind
import kotlinx.coroutines.launch

val subscription = session.subscribe(listOf(Filter(kinds = listOf(EventKind(1)), limit = 20)))
val collection = scope.launch {
    subscription.events.collect { event -> println(event.id.hex) }
}

if (session.awaitConnected()) {
    val status = session.publish(note.event).await(timeoutMs = 10_000)
    println(status)
}

subscription.close()
collection.cancel()
```

A subscription moves through `Starting`, `CatchingUp`, and `Live` after `EOSE`.
Deduplication belongs to each logical subscription: independent subscribers can
receive the same event. Policies and status flows let applications observe
retries, rejection, and closure. An operation rejected because the session is
closed or overloaded receives a terminal handle status.

## Relay pools

```kotlin
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelayPool
import io.github.nicolals.nostr.runtime.coroutines.RelayRole

val pool = CoroutinesRelayPool(
    codec = codec,
    eventVerifier = NostrEventVerifier(),
    transportFactory = transport,
    scope = scope,
)
pool.addRelay(RelayUrl.parse("wss://relay-one.example"), RelayRole.ReadWrite)
pool.addRelay(RelayUrl.parse("wss://relay-two.example"), RelayRole.Read)
pool.connectAll()
val merged = pool.subscribe(listOf(Filter(kinds = listOf(EventKind(1)), limit = 20)))
```

Pool subscriptions deduplicate copies across their selected relays. The read/write
roles control default routing; explicit relay selections still respect those roles.

## Optional modules and filters

```kotlin
import io.github.nicolals.nostr.nip42.Nip42Module
import io.github.nicolals.nostr.nip45.Nip45Module

val extendedSession = CoroutinesRelaySession(
    url = relayUrl,
    codec = codec,
    eventVerifier = NostrEventVerifier(),
    transportFactory = transport,
    scope = scope,
    additionalModules = listOf(Nip42Module(codec), Nip45Module(codec)),
)
```

NIP-01 is installed by the session. Other modules receive explicit dependencies;
there is no shared `NipModuleContext`. Use `session.events.upgrade(event)` for typed
event views, `session.submit(intent)` for reducer inputs, and `session.send(message)`
for custom client messages. [Recipes](recipes.md) includes AUTH and COUNT workflows.

NIP-50 search is an independent filter extension requiring no module registration:

```kotlin
import io.github.nicolals.nostr.nip50.withSearch

val query = Filter(kinds = listOf(EventKind(1)), limit = 20).withSearch("bitcoin")
```

Add the `nip50` artifact to use that helper. Other extensions can use
`Filter.extraFields`; codecs and REQ/COUNT conversions preserve them.

## Resource ownership

Close handles and sessions when finished. A session or pool borrows its transport
factory; the owner closes the factory after all its sessions are done.
`KtorRelayTransportFactory()` owns its internally created HTTP client. A factory
constructed with a supplied `HttpClient` borrows it; the caller must install Ktor's
WebSockets plugin and close that client.

```kotlin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

withContext(NonCancellable) {
    merged.close()
    pool.close()
    session.closeAndJoin()
    extendedSession.closeAndJoin()
    transport.closeAndJoin()
}
```

Put cleanup in `finally`. Parent-scope cancellation also terminates sessions and
their operations. An explicit join waits for transport cleanup to finish.
