# nostr-kmp

`nostr-kmp` is a Kotlin Multiplatform SDK for building Nostr clients, tools,
and protocol extensions. It separates the protocol model, codec, crypto,
transport, runtime, and NIP support into small modules so applications can
depend only on the pieces they use.

Current project version: `0.3.1-SNAPSHOT`.

## Modules

| Module | Purpose |
| --- | --- |
| `nostr-core` | Core event, key, filter, codec, transport, runtime, and NIP extension contracts. |
| `nostr-codec-kotlinx` | JSON and Nostr wire codec backed by `kotlinx.serialization`. |
| `nostr-crypto` | secp256k1 signing, hashing, ECDH, HKDF, AES-CBC, ChaCha20, HMAC, base64, and random bytes. |
| `nostr-transport-ktor` | Ktor WebSocket transport for JVM, Android, and iOS. |
| `nostr-runtime-coroutines` | Coroutine relay session and relay pool with reconnect, retry, deduplication, and observable flows. |
| `nips:nip01` | NIP-01 messages, metadata events, and protocol reducer. |
| `nips:nip04` | NIP-04 encrypted direct messages. |
| `nips:nip42` | NIP-42 relay authentication. |
| `nips:nip44` | NIP-44 v2 encrypted payload helpers. |
| `nips:nip45` | NIP-45 COUNT request and response messages. |
| `nips:nip47` | NIP-47 Nostr Wallet Connect events, payloads, and encryption selection. |

There is no aggregate `nostr-sdk` artifact in this repository today. Add the
specific modules your application needs.

## Installation

Snapshots are published under the `io.github.nicolals` group. Snapshot consumers
must add the Maven Central snapshot repository:

```kotlin
repositories {
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
    mavenCentral()
}
```

For a typical client using signing, JSON, Ktor transport, and the coroutine
runtime:

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

Add NIP modules only when needed:

```kotlin
commonMain.dependencies {
    implementation("io.github.nicolals:nip04:0.3.1-SNAPSHOT")
    implementation("io.github.nicolals:nip44:0.3.1-SNAPSHOT")
    implementation("io.github.nicolals:nip47:0.3.1-SNAPSHOT")
}
```

## Quick Start

This creates a signer, signs a text note, connects to one relay, publishes the
event, and closes the session.

```kotlin
import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.template.eventTemplate
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.transport.RelayUrl
import io.github.nicolals.nostr.crypto.NostrCrypto
import io.github.nicolals.nostr.crypto.NostrSigner
import io.github.nicolals.nostr.runtime.coroutines.CoroutinesRelaySession
import io.github.nicolals.nostr.transport.ktor.KtorRelayTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

suspend fun publishTextNote() {
    val codec = KotlinxNostrJsonCodec()
    val ctx = SimpleNipModuleContext(codec, NostrCrypto)
    val signer = requireOk(NostrSigner.generate())

    val note = signer.sign(
        eventTemplate(
            kind = EventKind(1),
            content = "Hello from nostr-kmp",
        ),
    )

    val session = CoroutinesRelaySession(
        url = RelayUrl.parse("wss://relay.example.com"),
        moduleContext = ctx,
        transportFactory = KtorRelayTransportFactory(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    session.connect()
    if (session.awaitConnected(timeoutMs = 10_000)) {
        val status = session.publish(note.event).await(timeoutMs = 10_000)
        println(status)
    }
    session.closeAndJoin()
}

private fun <T> requireOk(result: CryptoResult<T>): T = when (result) {
    is CryptoResult.Ok -> result.value
    is CryptoResult.Err -> error("Crypto operation failed: ${result.error}")
}
```

## Documentation

- [Getting Started](docs/getting-started.md): installation, signing, publishing, subscribing, and relay pools.
- [Modules](docs/modules.md): artifacts, dependencies, and NIP capabilities.
- [Architecture](docs/architecture.md): event model, codecs, NIP registries, transport, runtime, and policies.
- [Recipes](docs/recipes.md): focused examples for typed messages, metadata, encryption, COUNT, and NWC.
- [Publishing](docs/publishing.md): snapshot and release publishing instructions.

## Build and Test

Use the Gradle wrapper from the repository root:

```sh
./gradlew build
./gradlew check
./gradlew allTests
./gradlew jvmTest
./gradlew :nostr-core:build
./gradlew :nips:allTests
```

## Supported Targets

The modules are Kotlin Multiplatform libraries with common code plus JVM,
Android, and iOS targets. `nostr-transport-ktor` uses OkHttp on JVM and Android,
and Darwin on iOS.
