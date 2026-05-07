# Getting Started

This guide shows the shortest path from dependencies to a connected relay
session. The examples use common Kotlin APIs where possible, so the same
patterns apply on JVM, Android, and iOS.

Snippets that call `await...`, `addRelay`, `connectAll`, `subscribe`,
`publish`, or `close` are meant to run from a suspending scope.

## Add Dependencies

Snapshots require the Maven Central snapshot repository:

```kotlin
repositories {
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
    mavenCentral()
}
```

For a client that signs events and talks to relays:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nicolals:nostr-core:0.3.1-SNAPSHOT")
            implementation("io.github.nicolals:nostr-codec-kotlinx:0.3.1-SNAPSHOT")
            implementation("io.github.nicolals:nostr-crypto:0.3.1-SNAPSHOT")
            implementation("io.github.nicolals:nostr-transport-ktor:0.3.1-SNAPSHOT")
            implementation("io.github.nicolals:nostr-runtime-coroutines:0.3.1-SNAPSHOT")
        }
    }
}
```

Add NIP artifacts separately when you need typed extension support:

```kotlin
commonMain.dependencies {
    implementation("io.github.nicolals:nip04:0.3.1-SNAPSHOT")
    implementation("io.github.nicolals:nip42:0.3.1-SNAPSHOT")
    implementation("io.github.nicolals:nip44:0.3.1-SNAPSHOT")
    implementation("io.github.nicolals:nip47:0.3.1-SNAPSHOT")
}
```

## Create Shared SDK Services

Most modules need a `NipModuleContext`, which bundles the JSON/wire codec and
crypto provider.

```kotlin
import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.crypto.NostrCrypto

val codec = KotlinxNostrJsonCodec()
val ctx = SimpleNipModuleContext(codec, NostrCrypto)
```

## Sign an Event

Events are created as unsigned templates, then signed by an
`EventTemplateSigner`. `NostrSigner` is the built-in in-memory signer.

```kotlin
import io.github.nicolals.nostr.core.crypto.CryptoResult
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

private fun <T> requireOk(result: CryptoResult<T>): T = when (result) {
    is CryptoResult.Ok -> result.value
    is CryptoResult.Err -> error("Crypto operation failed: ${result.error}")
}
```

To restore an existing key, use `SecretKey.fromHex(...)` and
`NostrSigner.fromSecretKey(...)`.

## Connect to a Relay

`CoroutinesRelaySession` manages one relay connection. It installs NIP-01
internally for standard `EVENT`, `REQ`, `CLOSE`, `OK`, `EOSE`, `CLOSED`, and
`NOTICE` handling.

```kotlin
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val session = CoroutinesRelaySession(
    url = RelayUrl.parse("wss://relay.example.com"),
    moduleContext = ctx,
    transportFactory = KtorRelayTransportFactory(),
    scope = scope,
)

session.connect()
val connected = session.awaitConnected(timeoutMs = 10_000)
```

Observe connection changes with `connectionStatusFlow`, and call
`closeAndJoin()` when the session is no longer needed.

## Publish

```kotlin
import io.github.nicolals.nostr.core.runtime.PublishStatus

if (session.awaitConnected(timeoutMs = 10_000)) {
    val finalStatus = session.publish(note.event).await(timeoutMs = 10_000)

    when (finalStatus) {
        is PublishStatus.Accepted -> println("Accepted: ${finalStatus.message}")
        is PublishStatus.Rejected -> println("Rejected: ${finalStatus.message}")
        null -> println("Timed out waiting for relay OK")
        else -> println("Publish ended with $finalStatus")
    }
}
```

Publishing returns immediately with a handle. The handle exposes the current
`status`, a `statusFlow`, and `await()` helpers.

## Subscribe

```kotlin
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.primitives.EventKind
import kotlinx.coroutines.launch

val filter = Filter(
    kinds = listOf(EventKind(1)),
    limit = 20,
)

val handle = session.subscribe(listOf(filter))

val collectionJob = scope.launch {
    handle.events.collect { event ->
        println("${event.pubkey.hex}: ${event.content}")
    }
}

handle.awaitLive(timeoutMs = 10_000)
handle.close()
collectionJob.cancel()
```

The subscription starts in `Starting`, usually moves to `CatchingUp`, then
becomes `Live` after the relay sends `EOSE`. Use `statusFlow` when your UI or
service needs to react to those transitions.

## Use a Relay Pool

`CoroutinesRelayPool` manages multiple sessions and merges subscription events
with cross-relay deduplication.

```kotlin
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelayPool
import io.github.nicolals.nostr.runtime.coroutines.RelayRole
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory

suspend fun useRelayPool() {
    val pool = CoroutinesRelayPool(
        moduleContext = ctx,
        transportFactory = KtorRelayTransportFactory(),
        scope = scope,
    )

    pool.addRelay(RelayUrl.parse("wss://relay-one.example"), RelayRole.ReadWrite)
    pool.addRelay(RelayUrl.parse("wss://relay-two.example"), RelayRole.Read)
    pool.connectAll()

    val pooledSub = pool.subscribe(listOf(filter))
    val pooledPublish = pool.publish(note.event)

    val publishStatuses = pooledPublish.awaitAll()
    pooledSub.close()
    pool.close()
}
```

Relay roles control default routing: `Read`, `Write`, or `ReadWrite`. You can
also pass explicit relay URL sets to `subscribe(...)` or `publish(...)`.

## Add Optional NIP Modules

When constructing a session, pass additional modules for typed event/message
support beyond NIP-01:

```kotlin
import io.github.nicolals.nostr.nip42.Nip42Module
import io.github.nicolals.nostr.nip45.Nip45Module

val sessionWithExtensions = CoroutinesRelaySession(
    url = RelayUrl.parse("wss://relay.example.com"),
    moduleContext = ctx,
    transportFactory = KtorRelayTransportFactory(),
    scope = scope,
    additionalModules = listOf(
        Nip42Module(ctx),
        Nip45Module(ctx),
    ),
)
```

Upgrade received events with `session.events.upgrade(event)` when you need a
typed `EventView`.
