# nostr-core Module Documentation

`nostr-core` is the heart of the nostr-kmp ecosystem. It contains:

- **Canonical data model**: NIP-01 compliant events, filters, wire messages, and strongly typed identifiers.
- **Cryptographic contracts**: minimal interfaces for hashing, signing, key derivation, and verification without binding to a specific backend.
- **Session state machine**: an immutable reducer that models client <-> relay interactions, producing commands and outputs that higher layers can execute.

Everything else in the repository—codecs, secp256k1 backends, coroutine runtimes, transports—builds on top of these foundations. `nostr-core` is multiplatform, dependency-free, and designed so you can bring your own crypto/runtime stack.

## Architecture Overview

```
nostr-core
├── crypto/          // Hashing & signing abstractions, value classes for keys/signatures
├── identity/        // Identity facade combining signers/verifiers and event builders
├── model/           // NIP-01 data structures (events, filters, client/relay messages)
├── session/         // Intent → state reducer with commands and outputs
├── time/            // Clock abstraction used by builders and tests
└── utils/           // Hex helpers and validation utilities
```

### Key Design Points

- **No dependencies**: the module uses only the Kotlin standard library and expect/actual for `Sha256`. All platform bindings live in other modules.
- **Value classes for invariants**: `SubscriptionId`, `PublicKey`, `Signature`, and `EventId` guarantee hex/length constraints at construction time.
- **Layered responsibilities**: codecs parse JSON, `nostr-core` enforces protocol rules, runtimes execute commands. This keeps each concern testable and swappable.

## Using nostr-core in the Ecosystem

### 1. Combine with Crypto (`nostr-crypto`)

```kotlin
import nostr.core.identity.Identity
import nostr.crypto.Identity as SecpIdentity

val signer: Identity = SecpIdentity.fromPrivateKeyHex(
    "1f...",
)

val event = signer.eventBuilder()
    .kind(1)
    .addTag("p", signer.publicKey.toString())
    .content("hello nostr")
    .build()

check(signer.verify(event))
```

Here, `nostr-core` provides the builder and identity facade; `nostr-crypto` supplies the secp256k1 implementation backing the `Signer`/`SignatureVerifier` interfaces.

### 2. Encode/Decode (`nostr-codec-kotlinx-serialization`)

```kotlin
import nostr.codec.kotlinx.serialization.KotlinxSerializationWireCodec
import nostr.core.model.ClientMessage

val codec = KotlinxSerializationWireCodec.default()
val json = codec.clientMessage(ClientMessage.Event(event))
val parsed = codec.clientMessage(json)
```

The codec turns `nostr-core` objects into wire payloads (and back). Parsing errors return `WireDecodingException` for client-originated messages or `RelayMessage.Unknown` for relay-originated frames.

### 3. Run the Session Engine (`nostr-runtime-coroutines`)

```kotlin
import kotlinx.coroutines.*
import nostr.core.session.*
import nostr.runtime.coroutines.CoroutineNostrRuntime

val runtime = CoroutineNostrRuntime(
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    connectionFactory = /* Ktor adapter or your connection */,
    wireEncoder = codec,
    wireDecoder = codec,
    settings = RelaySessionSettings(
        maxEventReplayIds = 100,
        maxPublishStatuses = 100,
        verifyEventIds = true
    )
)

runtime.connect("wss://relay.example")
runtime.subscribe("sub-id", listOf(Filter(kinds = setOf(1))))
```

The runtime wraps `RelaySessionEngine`, dispatches intents, executes commands (open sockets, send frames), and exposes `SharedFlow<RelaySessionOutput>` with relay events, acknowledgements, and errors.

### 4. Building Your Own Modules

- **Custom Crypto**: Implement `nostr.core.crypto.Signer`, `SignatureVerifier`, and `Sha256` to hook hardware wallets or alternative libraries into `nostr-core`.
- **Custom Codec**: If you prefer another JSON backend or binary wire format, encode/decode directly to `nostr-core` models. Enforce validation in the same places (`Event`, `Filter`, `SubscriptionId`).
- **Custom Runtime**: If coroutines or the provided transports don’t fit, embed `RelaySessionEngine` in your environment. Feed `RelaySessionIntent`s and consume `RelaySessionCommand`s/`RelaySessionOutput`s.

## Session Reducer Configuration

`RelaySessionSettings` exposes the main tuning knobs:

| Setting              | Default | Description |
|----------------------|---------|-------------|
| `maxEventReplayIds`  | 200     | Replay buffer per subscription for duplicate suppression. `0` disables dedupe. |
| `maxPublishStatuses` | 200     | How many publish acknowledgements to keep in memory. |
| `verifyEventIds`     | false   | Recompute canonical event ids on every relay `EVENT` frame and reject mismatches. |
| `canonicalHasher`    | SHA-256 | Hasher used when `verifyEventIds` is enabled. |

### Engine Edge Cases & Behaviour

- **Offline unsubscribe**: subscriptions marked `Closing` while disconnected stay intact but emit a deferred `CLOSE` when the connection returns. Pending entries aren’t reopened unless the relay acknowledges with a `CLOSED` frame.
- **Closed subscriptions**: `SubscriptionStatus.Closed` entries are left untouched on reconnect; no new `REQ` is issued.
- **Duplicate events**: `receivedEventIds` keeps the most recent ids per subscription. The helper is `O(n)` for the configured window; tune `maxEventReplayIds` according to traffic.
- **Publish acknowledgements**: `publishStatuses` maps event id → `PublishStatus` (`Pending` or `Acknowledged`). Entries older than `maxPublishStatuses` roll off automatically.
- **Unknown relay frames**: produce `RelaySessionOutput.Error(EngineError.ProtocolViolation)` while leaving state unchanged.
- **Verification errors**: when `verifyEventIds` is enabled, mismatched hashes emit an error output and suppress delivery. The reducer does not drop the subscription.

## NIP-01 Compliance Highlights

See `nostr-core/doc/nip-01-review.md` for the living checklist, but core coverage includes:

- Canonical event serialization with correct escaping.
- Subscription/filter validation for hex lengths and tag shapes (single-letter by default).
- Optional canonical id verification in the reducer.
- Normalized handling of `OK`/`CLOSED` messages exposing machine-readable codes and human-readable text.

## Testing

`nostr-core/src/commonTest` includes:

- `EventValidationTest`/`FilterValidationTest` for constructor invariants.
- `ReducerTest` covering connection lifecycle, dedupe windows, publish queueing, canonical verification, and reconnect-edge cases.

To run the suite:

```bash
./gradlew :nostr-core:check
```

## Terminology

- **Intent** – Input to the reducer (connect, subscribe, relay frame).
- **Command** – Action the runtime must execute (open socket, send frame, emit output).
- **Output** – Notification surfaced to application code (event delivered, subscription terminated, error).

Questions or pull requests? See the repository root `README.md` for contribution guidelines and module overviews.
