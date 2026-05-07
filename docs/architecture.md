# Architecture

The SDK is split around a small set of stable boundaries: event model, codecs,
NIP modules, transport, and runtime. The goal is to keep protocol semantics
testable without forcing a specific network stack or concurrency model.

## Event Model

`nostr-core` owns the fundamental domain types:

- `Event`: signed Nostr event.
- `EventDraft`: unsigned event fields that can be signed.
- `EventTemplate<T : EventView>`: unsigned draft plus a typed wrapper factory.
- `EventView`: typed view over an `Event`.
- `GenericEvent`: fallback `EventView` for events without a registered upgrader.
- `EventId`, `PublicKey`, `SecretKey`, `EventSig`, `EventKind`, `SubscriptionId`: validated value classes.
- `Filter`: NIP-01 subscription filter.

Templates preserve type information through signing:

```kotlin
val template = eventTemplate(
    kind = EventKind(1),
    content = "hello",
)

val signed: GenericEvent = signer.sign(template)
val event = signed.event
```

Typed views should be cheap to construct. If a NIP view needs expensive parsing
or decryption, keep it lazy or pass known parsed values from the template or
upgrader.

## Codecs

`nostr-core` defines codec contracts and a small JSON value model:

- `JsonCodec`: parse and stringify `JsonValue`.
- `NostrWireCodec`: encode and decode wire events, filters, and raw messages.
- `NostrJsonCodec`: combined JSON and wire codec with string helpers.

`nostr-codec-kotlinx` implements `NostrJsonCodec` with
`KotlinxNostrJsonCodec`. It decodes protocol messages to raw message arrays.
Typed interpretation is intentionally delegated to NIP upgraders.

## NIP Modules

Each NIP module can provide:

- event upgraders
- client message upgraders
- relay message upgraders
- protocol reducers

The common interface is `NipModule`. A `NipModuleContext` gives modules access
to JSON, wire encoding, and crypto primitives.

Install modules into a `NipRegistry` when you need all registries built
together:

```kotlin
val registry = NipRegistry.Builder(codec)
    .install(Nip01Module(ctx))
    .install(Nip42Module(ctx))
    .build()

val eventView = registry.events.upgrade(event)
val relayMessage = registry.messages.upgradeRelay(rawRelayMessage)
```

Module order matters for protocol reducers. Install dependency modules first.
For example, NIP-01 should be installed before NIP-42.

## Protocol Engine

The core protocol engine is a reducer-based finite state machine. It consumes
`ProtocolInput` values, updates `ProtocolState`, and emits `ProtocolEffect`
values such as sending a message or scheduling a timer.

Most application code uses `CoroutinesRelaySession` instead of driving the
engine directly. The engine remains useful for tests, alternate runtimes, or
custom transports.

## Transport

`nostr-core` defines transport interfaces:

- `RelayUrl`: validated `ws://` or `wss://` relay URL.
- `RelayConnection`: single WebSocket connection.
- `RelayConnectionListener`: open, message, pong, close, and failure callbacks.
- `RelayTransportFactory`: creates connections.
- `TransportResult`, `TransportError`, `TransportState`, and `TransportConfig`.

`nostr-transport-ktor` implements those contracts with Ktor WebSockets. A
connection instance is single-use; create a new connection for reconnects.

## Coroutine Runtime

`nostr-runtime-coroutines` provides the main client-facing runtime:

- `CoroutinesRelaySession`: one relay connection.
- `CoroutinesRelayPool`: multiple relay sessions with read/write roles.
- `CoroutinesSubscriptionHandle`: subscription status and event `Flow`.
- `CoroutinesPublishHandle`: publish status and `await()` helpers.
- pool handles for merged subscriptions and per-relay publish statuses.

The session serializes state changes through an internal actor coroutine. Public
methods are safe to call from multiple coroutines.

## Runtime Policies

`SessionConfig` controls runtime behavior:

- `ReconnectPolicy`: reconnect delay and give-up behavior after disconnects.
- `RetryPolicy`: retry interrupted publishes and subscriptions after reconnect.
- `DeduplicationPolicy`: filter duplicate events.
- `ClosePolicy`: client-initiated subscription close behavior.
- `pingIntervalMillis`: optional WebSocket ping interval.

Defaults are suitable for most clients: exponential reconnect, publish retry,
resubscribe after disconnect, LRU event deduplication, immediate close, and no
automatic pings.

## Extending the SDK

To add a NIP module:

1. Create event or message view types for the protocol surface.
2. Implement `EventUpgrader`, `ClientMessageUpgrader`, or `RelayMessageUpgrader`.
3. Add a `ProtocolReducer` only if the NIP changes connection, publish, or subscription behavior.
4. Bundle the pieces in a `NipModule`.
5. Add common tests around wire parsing, typed upgrades, and reducer behavior.

Keep constructors lightweight and use the existing `NipModuleContext` services
instead of adding codec or crypto singletons to each module.
