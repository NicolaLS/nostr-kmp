# Architecture

The SDK separates event models, codecs, cryptographic capabilities, protocol
modules, transport, and runtime. NIP modules are optional. There is no aggregate
SDK artifact and no global service context.

## Events and trust

`EventDraft` contains unsigned fields. `EventTemplate<T>` pairs a draft with a
cheap typed view factory. `EventTemplateSigner.sign(template)` suspends and
returns `CryptoResult<T>`; the default convenience method checks that a custom
signer returned the requested draft and public key before wrapping it.

Drafts and events own immutable snapshots of both tag-list levels. `Event` is a
structural model: constructing or decoding one does not authenticate it.
`EventVerifier` defines the trust boundary. `NostrEventVerifier` computes the
canonical NIP-01 ID and verifies the Schnorr signature against that ID.

Relay sessions require a verifier and apply it before emitting events, processing
protocol event signals, or recording deduplication IDs. Wallet clients additionally
validate the configured wallet author, client recipient, and request correlation.
Applications implementing other authenticated workflows must also enforce their
own expected identities and protocol-specific constraints.

Sessions decode standard NIP-01 message envelopes through the built-in decoder;
custom message upgraders cannot shadow EVENT verification or OK/EOSE routing.
Use event upgraders to interpret verified event contents. Custom wire message
tokens still use the installed message registry.

## Cryptographic capabilities

`CryptoPrimitives` and `SigningPrimitives` expose local algorithms for low-level
implementations. `EventTemplateSigner` exposes an account's asynchronous signing
capability without requiring private-key export. Optional `Nip04Cipher` and
`Nip44Cipher` interfaces provide asynchronous encryption/decryption with a peer
public key; local implementations accept a `SecretKey` and a crypto provider.
NIP-47 composes these through `NwcCipher`.

A remote signer can implement those capabilities with network calls or user
approval. Cancellation propagates. Expected failures use `CryptoResult.Err`.
Private keys, keypairs, message keys, and successful crypto-result diagnostic
strings are redacted. Explicit raw-key access remains available to callers who
need persistence or low-level primitives; those values must not be logged.

NIP-44 payload helpers enforce authenticated decryption and resource limits. The
supported maximum plaintext is 65,535 UTF-8 bytes. Low-level encryption does not
replace outer-event signature verification or application-level sender checks.
NIP-04 is retained for compatibility and needs a verified signed event because
its ciphertext alone does not authenticate the sender or message.

## Codecs and filters

`JsonCodec` handles the SDK's JSON value model; `NostrWireCodec` handles wire
events, filters, and raw message arrays. `NostrJsonCodec` combines those contracts.
`KotlinxNostrJsonCodec` is the default implementation.

Numbers preserve their source literal. Conversion to bounded integers is exact:
fractional values and overflow are rejected before exponent expansion can allocate
large buffers. Unknown filter fields survive wire and domain conversions through
`extraFields`. Extensions cannot override standard fields or tag filters.
`nip50` supplies typed search accessors without changing the core codec.

## NIP modules

A `NipModule` can contribute event upgraders, client/relay message upgraders, and
pure protocol reducers. `NipRegistry.Builder(codec).install(module)` installs all
those pieces together. Unknown event kinds and message tokens remain available as
generic views.

Every module declares a stable `id` and any `requires` module IDs. Duplicate IDs
are rejected on installation; missing or incorrectly ordered dependencies are
rejected on build. Sessions and pools require `nip01`, while standalone registries
may contain only the decoders an application needs. Do not include NIP-01 in
`additionalModules`: convenience constructors already install it.

Module state keys must be stable instances, normally singleton objects. The store
checks key identity before reading or replacing a namespaced slot and rejects
collisions explicitly. Its diagnostics list keys without exposing stored values.

Dependencies are explicit: `Nip01Module(codec)` uses JSON and wire codecs;
`Nip42Module(codec)` and `Nip45Module(codec)` require only wire encoding;
`Nip04Module()` needs no service to interpret an event; `Nip47Module(selector)`
needs only its encryption selector. NIP-44 provides crypto capabilities, so there
is no empty registry module to install.

## Protocol engine and extensions

`ProtocolEngine` threads immutable state through registered reducers, with
module dependencies installed first. Every reducer can observe lifecycle inputs;
reducers cannot suppress one another. Shared reducers keep per-session state in
`ProtocolState`. Effects describe sending a message or managing a timer and are
executed by the runtime.

Each transport opening supplies a fresh `ConnectionContext` containing the relay
URL. NIP-42 challenge tokens bind a response to that context and the exact challenge
occurrence. Stale tokens, mismatched tags, and invalid auth event shapes are rejected
locally without sending. The signer remains an explicit application choice.

Modules can contribute `admissionPolicies`. Before any reducer handles an
`OperationIntent`, these policies return `Allow`, `Reject(reason)`, or
`Defer(reason)`. Rejection takes precedence over deferral. Admission runs before
all reducers regardless of module order, including for `additionalModules`.
Lifecycle and cleanup inputs never pass through admission.

Managed publish/subscribe handles expose `Deferred` while waiting. The session
owns these operations and automatically reevaluates them when protocol state
changes. Rejection completes the handle; closing a deferred subscription removes
it. Policies must be pure and use protocol state rather than mutable external
flags. Raw `send` is a transport-level escape hatch and bypasses admission.

Call a publish handle's `cancel()` to remove queued/deferred work and stop retries.
Its terminal `Cancelled(mayHaveBeenSent)` status distinguishes cancellation before
publication from an uncertain outcome after sending. Cancelling or timing out
`await()` alone stops observation. Pool publish handles offer the same cancellation
operation; `close()` only stops their status tracking.

The coroutine runtime exposes `submit(ProtocolIntent)` and `send(ClientMessageView)`.
`submit` cannot inject synthetic inbound messages or transport lifecycle inputs.
It returns an admission decision; custom submitted operations are caller-owned
and must be resubmitted explicitly after deferral. Unsupported intents are rejected.
Cancellation of `submit` or `send` races atomically with actor admission: cancellation
while queued prevents execution. Once actor processing starts, cancellation stops
waiting but cannot recall an effect. Observe protocol responses to determine remote
outcomes.
Its `signals` and `messages` flows expose all installed extensions. An application
can implement a new reducer or issue COUNT/AUTH commands without changing runtime
source code. Start collecting before submitting the corresponding operation.
Each active observer has its own bounded buffer. A slow observer fails with
`SessionObservationOverflowException`; it cannot silently lose a response or block
session shutdown. Streams complete when the session closes.

## Runtime and resource ownership

The session actor serializes state changes. Bounded operation admission fails
rejected handles explicitly; lifecycle commands remain deliverable under load.
An unexpected reducer, verifier, or effect-processing exception permanently closes
the affected session, completes its handles, and leaves its connection status
`Failed`. Diagnostics omit exception payloads. Malformed wire messages remain
recoverable parse errors. Create a new session after correcting a processing fault.
Transport callbacks carry a connection generation, and termination is processed
once per connection. Timers execute scheduled retries instead of recalculating
their delays indefinitely.

`SessionConfig` stores a deduplication factory, not a live cache. Each logical
subscription owns its cache, and pooled subscriptions separately deduplicate their
merged events across relays. Reusing a configuration does not share mutable state.

Subscription buffers default to 256 events. Overflow closes the affected
subscription and fails its event flow with `SubscriptionBufferOverflowException`,
so an incomplete history cannot appear successful. Configure
`subscriptionBufferCapacity` for the workload. Applications that explicitly accept
loss can select `SubscriptionOverflowPolicy.DropNewest` and observe the handle's
`droppedEventsFlow`. Deduplication records IDs only after buffer admission; dropped
events remain eligible for later replay. These policies also apply to each
underlying subscription in a relay pool.

The NWC client converts response/notification stream failures into a typed client
failure, fails pending requests, and closes the affected pool. It does not remain
Ready with an abandoned event collector. Requests already published retain an
uncertain outcome rather than being reported as definitely unsent.

Sessions and pools borrow their transport factory. The factory owner closes it
when all sessions are finished. `KtorRelayTransportFactory()` owns its default
WebSockets-enabled HTTP client; a supplied client is borrowed. Closing a connection
works before, during, or after its handshake. Parent-scope cancellation cleans up
session operations and transport resources; explicit `closeAndJoin()` allows
callers to await that cleanup.
