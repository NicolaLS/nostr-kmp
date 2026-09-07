# Modules

All published artifacts use the `io.github.nicolals` group and the root project
version. For the current snapshot, use `0.3.3-SNAPSHOT`.

## Core Modules

| Artifact | Gradle project | Depends on | Description |
| --- | --- | --- | --- |
| `nostr-core` | `:nostr-core` | Okio | Core protocol types, value classes, codec contracts, transport contracts, runtime contracts, protocol engine, and NIP module contracts. |
| `nostr-codec-kotlinx` | `:nostr-codec-kotlinx` | `nostr-core`, `kotlinx.serialization-json` | Implements `NostrJsonCodec` for JSON values, events, filters, and raw client/relay messages. |
| `nostr-crypto` | `:nostr-crypto` | `nostr-core`, libsodium, `secp256k1-kmp` | Implements `NostrCrypto`, `NostrSigning`, and `NostrSigner`. |
| `nostr-transport-ktor` | `:nostr-transport-ktor` | `nostr-core`, Ktor client WebSockets, coroutines | Implements `RelayTransportFactory` using Ktor WebSockets. |
| `nostr-runtime-coroutines` | `:nostr-runtime-coroutines` | `nostr-core`, `nip01`, coroutines | Provides `CoroutinesRelaySession`, `CoroutinesRelayPool`, publish handles, and subscription handles. |

The `:nips` Gradle project is an aggregate lifecycle project for local builds.
It is not an SDK artifact with source code.

## NIP Modules

| Artifact | Gradle project | Description |
| --- | --- | --- |
| `nip01` | `:nips:nip01` | NIP-01 client and relay messages, metadata events, and core protocol reducer. |
| `nip04` | `:nips:nip04` | Kind `4` encrypted direct message event view plus NIP-04 encrypt/decrypt helpers. |
| `nip42` | `:nips:nip42` | Relay AUTH challenge/client message upgraders, kind `22242` auth event, and auth protocol reducer. |
| `nip44` | `:nips:nip44` | NIP-44 v2 conversation keys, payload helpers, and asynchronous local/remote cipher capabilities. |
| `nip45` | `:nips:nip45` | COUNT client and relay message views. |
| `nip47` | `:nips:nip47` | Nostr Wallet Connect events, JSON payload codecs, and injected NIP-04/NIP-44 encryption. |
| `nip50` | `:nips:nip50` | Typed search filter extensions; no registry installation required. |

## Wallet Client

The [`nwc-kmp`](../nwc-kmp/README.md) artifact (`:nwc-kmp`) provides a high-level
Nostr Wallet Connect client built on the core, codec, crypto, transport,
coroutine runtime, and NIP-04/44/47 projects in this repository. It shares the
root version and publishing configuration. Applications can depend on it directly:

```kotlin
commonMain.dependencies {
    implementation("io.github.nicolals:nwc-kmp:0.3.3-SNAPSHOT")
}
```

## Common Dependency Sets

Signing and local event construction:

```kotlin
commonMain.dependencies {
    implementation("io.github.nicolals:nostr-core:0.3.3-SNAPSHOT")
    implementation("io.github.nicolals:nostr-crypto:0.3.3-SNAPSHOT")
}
```

Wire encoding and decoding:

```kotlin
commonMain.dependencies {
    implementation("io.github.nicolals:nostr-core:0.3.3-SNAPSHOT")
    implementation("io.github.nicolals:nostr-codec-kotlinx:0.3.3-SNAPSHOT")
}
```

Relay client runtime:

```kotlin
commonMain.dependencies {
    implementation("io.github.nicolals:nostr-codec-kotlinx:0.3.3-SNAPSHOT")
    implementation("io.github.nicolals:nostr-crypto:0.3.3-SNAPSHOT")
    implementation("io.github.nicolals:nostr-transport-ktor:0.3.3-SNAPSHOT")
    implementation("io.github.nicolals:nostr-runtime-coroutines:0.3.3-SNAPSHOT")
}
```

Nostr Wallet Connect:

```kotlin
commonMain.dependencies {
    implementation("io.github.nicolals:nostr-codec-kotlinx:0.3.3-SNAPSHOT")
    implementation("io.github.nicolals:nostr-crypto:0.3.3-SNAPSHOT")
    implementation("io.github.nicolals:nip47:0.3.3-SNAPSHOT")
}
```

`nip47` brings `nip04` and `nip44` as API dependencies.

## Installing NIP Modules

NIP modules expose upgraders and protocol reducers. Install them into
`NipRegistry` when working at the registry level:

```kotlin
import io.github.nicolals.nostr.core.nip.NipRegistry
import io.github.nicolals.nostr.nip01.Nip01Module
import io.github.nicolals.nostr.nip42.Nip42Module
import io.github.nicolals.nostr.nip45.Nip45Module

val registry = NipRegistry.Builder(codec)
    .install(Nip01Module(codec))
    .install(Nip42Module(codec))
    .install(Nip45Module(codec))
    .build()
```

Install them on coroutine sessions with `additionalModules`. The session always
installs NIP-01 first.

```kotlin
val session = CoroutinesRelaySession(
    url = relayUrl,
    codec = codec,
    eventVerifier = NostrEventVerifier(),
    transportFactory = factory,
    scope = scope,
    additionalModules = listOf(
        Nip42Module(codec),
        Nip45Module(codec),
    ),
)
```

Modules take only the services they need. `Nip04Module()` needs no context,
and NIP-44 exposes cipher capabilities without an empty registry module.

NIP-47 needs an encryption selector. Use NIP-04 as the fallback until a wallet
info event advertises NIP-44 support; this matches the compatibility behavior
used by the higher-level `nwc-kmp` client.

```kotlin
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.nip47.Nip47Module
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nostr.nip47.nwcEncryptionSelectorByWalletPubkey

val selector = nwcEncryptionSelectorByWalletPubkey(
    mapping = emptyMap<PublicKey, NwcEncryption>(),
    fallback = NwcEncryption.NIP04,
)

val module = Nip47Module(selector)
```

When you have a wallet kind `13194` info event, use
`NwcInfoEvent.preferredEncryption()` to prefer NIP-44 only when the wallet
advertises it.

## Platform Notes

All modules use common Kotlin source sets plus JVM, Android, and iOS targets.
`nostr-transport-ktor` creates default platform clients with:

- JVM: Ktor CIO engine
- Android: Ktor CIO engine
- iOS: Ktor Darwin engine

Provide your own Ktor `HttpClient` with the WebSockets plugin installed to
customize timeouts, headers, proxy settings, or engine configuration. Supplied
HTTP clients and transport factories are borrowed; their owner closes them.
The default Ktor factory owns its internally created client.
