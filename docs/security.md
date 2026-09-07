# Security contracts

## Authenticate events before acting on them

`Event`, wire decoding, and NIP event upgraders describe data; they do not establish
trust. Use `NostrEventVerifier` to check both the canonical event ID and Schnorr
signature. Coroutine relay sessions require an `EventVerifier` and verify events
before delivery or deduplication. A custom verifier becomes part of the
application's trust boundary.

A valid signature identifies the signing key. Applications must still check the
expected author, recipient, kind, request correlation, and any expiration rules
for their workflow. The NWC client performs wallet identity, recipient, and request
correlation checks before accepting responses. Filters sent to a relay are not
an authentication mechanism.

## Choose encryption through the protocol

Prefer NIP-44 where the peer protocol supports it. Its payload authentication does
not replace verification of the outer Nostr event and its expected identities.
NIP-04 ciphertext is unauthenticated; verify the signed event before decrypting it.
The NIP-04 event helper requires a verifier for this reason.

The default local ciphers generate fresh randomness. Low-level overloads accepting
a nonce or IV are intended for protocol implementations and test vectors; callers
must never reuse a NIP-44 nonce with the same conversation key or supply predictable
NIP-04 IVs. NIP-44 helpers bound plaintext to 65,535 UTF-8 bytes and validate payload
lengths, MACs, padding, and UTF-8 before returning plaintext.

## Keep account capabilities and secrets separate

`EventTemplateSigner`, `Nip04Cipher`, `Nip44Cipher`, and `NwcCipher` can operate with
remote or hardware-held keys. The application owns the provider's authorization
policy and secure communication. Implementations should propagate coroutine
cancellation and return expected failures through `CryptoResult`.

Secret wrappers and crypto-result diagnostics redact key material and plaintext.
Explicit byte access and credential URI serialization still return sensitive data.
Do not log or place those values in analytics. Store NWC connection URIs as wallet
credentials, and use a dedicated connection identity rather than a user's public
Nostr identity. The secretless `NwcConnection` API supports providers that retain
the private key elsewhere.

Local keys and plaintext use managed Kotlin objects. The SDK does not promise
secure memory erasure of strings, immutable byte strings, or their copies. Applications
requiring that property should keep keys in a suitable external provider.

Relay authentication discloses the signing identity to the relay. The SDK exposes
AUTH challenges to application policy and does not automatically approve them.

## Validation

Common tests cover published BIP-340 and NIP-44 vectors, primitive known answers,
malformed ciphertext, tampering with signed fields, immutable event tags, and secret
redaction. Wallet and runtime regression tests exercise spoofed events, request
correlation, cancellation, reconnects, and overload. Run both `jvmTest` and
`iosSimulatorArm64Test`: platform cryptographic providers can differ in behavior.

These tests and this repository review are not an independent cryptographic audit.
