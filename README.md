# Objective

Provide a [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) *Nostr* SDK that developers can adopt
with **confidence**. Layers of abstraction are used by the SDK itself, but can also be used by developers to "branch
off" for a more tailored solution. Designed to **allow customization** while reducing duplicated efforts and bugs to a
minimum.

# Packages

- `nostr-core`: Core/Base Protocol
- `nostr-codec-kotlinx-serialization`: Default implementation for core's `WireEncoder` and `WireDecoder` using `kotlinx.serialization-json`
- `nostr-transport-ktor`: Default implementation for core's `RelayConnection` and `RelayConnectionFactory` using `ktor-client-core` and `ktor-client-websocket`
- `nostr-runtime-coroutines`: Runtime implementation using coroutines
- `nostr-crypto`: secp256k1 stuff for nostr protocol, will be moved to core and the `secp256k1-kmp` dependency will be dropped
