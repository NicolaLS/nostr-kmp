# Migrating from the pre-review snapshot

These changes follow commit `2cfd4eb`. They are breaking SDK changes even though
the development version remains `0.3.3-SNAPSHOT`. Rebuild downstream modules
against a consistent set of SDK artifacts; Kotlin default-argument methods and
data-class constructors are not binary compatible with every previous snapshot.

## High-level NWC clients

Existing `NwcClient(uri, scope, httpClient, cachedWalletInfo)` calls and typed methods
such as `payInvoice`, `lookupInvoice`, and `getBalance` remain source-compatible.
Added injection parameters have defaults.

`NwcClient.uri` is now nullable because an account can use a remote signer without
exporting a private key or constructing a credential URI. Use `client.connection`
for public wallet/client identities and relay routing. When a workflow explicitly
requires the credential URI of a locally created client, check `client.uri` for
null. `WalletDiscovery.uri` remains non-null.

Behavior changes to account for:

- `close()` is terminal. Construct a new client to reopen the connection.
- A positive request timeout covers readiness, encryption, signing, publication,
  and response. `payInvoice(verifyOnTimeout = true)` can still perform its separate
  verification lookup afterward. Discovery also uses one overall deadline.
- Caller cancellation propagates. SDK-owned interruption returns a typed failure;
  after publication becomes possible, an interrupted payment remains uncertain.
  A timeout does not establish that a payment failed or that retrying is safe.
- Malformed or unauthenticated wallet events, mismatched recipients, and invalid
  response correlations are ignored. Custom transports must deliver valid signed
  events even in tests.
- URI parsing rejects invalid private scalars. Credential diagnostic strings are
  redacted; persist the explicit `raw` URI rather than `toString()`.
- Supplied HTTP clients and transport factories remain caller-owned. The default
  transport owns its client and uses CIO on JVM/Android and Darwin on iOS. Custom
  clients must install WebSockets and support cancellation of unfinished upgrades.

## Lower-level SDK use

- Replace `NipModuleContext` with explicit codecs and signing/encryption capabilities.
- `NipRegistry` replaces `NipStack`. `Nip01Module(codec)`, `Nip42Module(codec)`,
  `Nip45Module(codec)`, and `Nip47Module(selector)` take their actual dependencies.
  Use `Nip04Module()`; NIP-44 no longer has an empty registry module.
- Signing suspends and returns `CryptoResult`. Create local signers through
  `NostrSigner.fromSecretKey(...)` or `NostrSigner.generate()`.
- NIP-04/44/47 encrypted templates use asynchronous cipher capabilities.
- Relay sessions and pools require an event verifier. Configure deduplication
  with `deduplicationPolicyFactory`, which supplies a separate cache per subscription.
- Event models snapshot their tags. They retain explicit `copy` and value equality,
  but are no longer data classes with generated destructuring functions.

See [Getting Started](getting-started.md), [Recipes](recipes.md), and
[Security](security.md) for the current APIs and trust boundaries.
