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

Dependencies are explicit: `Nip01Module(codec)` uses JSON and wire codecs;
`Nip42Module(codec)` and `Nip45Module(codec)` require only wire encoding;
`Nip04Module()` needs no service to interpret an event; `Nip47Module(selector)`
needs only its encryption selector. NIP-44 provides crypto capabilities, so there
is no empty registry module to install.

## Protocol engine and extensions

`ProtocolEngine` threads immutable state through registered reducers. Inputs and
signals are open interfaces. Effects describe sending a message or managing a
timer; effects are executed by the runtime. Reducer registration order is explicit,
with dependencies installed first. The session installs NIP-01 before additional
modules.

The coroutine runtime exposes `submit(ProtocolInput)` and `send(ClientMessageView)`.
Its `signals` and `messages` flows expose all installed extensions. An application
can implement a new reducer or issue COUNT/AUTH commands without changing runtime
source code. Start collecting before submitting the corresponding operation.
Each active observer has its own bounded buffer. A slow observer fails with
`SessionObservationOverflowException`; it cannot silently lose a response or block
session shutdown. Streams complete when the session closes.

## Runtime and resource ownership

The session actor serializes state changes. Bounded operation admission fails
rejected handles explicitly; lifecycle commands remain deliverable under load.
Transport callbacks carry a connection generation, and termination is processed
once per connection. Timers execute scheduled retries instead of recalculating
their delays indefinitely.

`SessionConfig` stores a deduplication factory, not a live cache. Each logical
subscription owns its cache, and pooled subscriptions separately deduplicate their
merged events across relays. Reusing a configuration does not share mutable state.

Sessions and pools borrow their transport factory. The factory owner closes it
when all sessions are finished. `KtorRelayTransportFactory()` owns its default
WebSockets-enabled HTTP client; a supplied client is borrowed. Closing a connection
works before, during, or after its handshake. Parent-scope cancellation cleans up
session operations and transport resources; explicit `closeAndJoin()` allows
callers to await that cleanup.
