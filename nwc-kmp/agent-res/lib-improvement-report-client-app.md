# NWC-KMP Improvement Plan

  ## Context
  - Reviewed `papp`, a Compose Multiplatform wallet that uses `nwc-kmp` to manage Nostr Wallet Connect (NIP-47) sessions for invoice payments.
  - Cross-referenced the current `nwc-kmp` implementation and NIP-47 (`resources/nips/47.md`) to understand supported commands and protocol expectations.
  - Goal: capture ergonomic gaps, missing abstractions, and future capabilities that would make the `nwc-kmp` client easier to integrate for papp and other wallet-style apps. Backwards compatibility is not a constraint.

  ## Current Usage Patterns in papp
  - URI validation is done by calling `parseNwcUri` everywhere the user can paste/scan a connection string (`papp/composeApp/src/commonMain/kotlin/xyz/lilsus/papp/presentation/settings/add_wallet/AddWalletViewModel.kt:19-64`).
  - When persisting a connection the app parses the URI twice: once through `parseNwcUri` for relay/lud16, then again via custom code to extract the wallet pubkey string (`papp/composeApp/src/commonMain/kotlin/xyz/lilsus/papp/
  domain/use_cases/SetWalletConnectionUseCase.kt:18-34`).
  - Wallet discovery (alias/capabilities/network) spins up a short-lived `NwcClient`, refreshes metadata, calls `getInfo`, maps the raw responses, then closes the client manually (`papp/composeApp/src/commonMain/kotlin/xyz/
  lilsus/papp/data/nwc/WalletDiscoveryRepositoryImpl.kt:33-87`).
  - Active payments reuse a cached `NwcClient` that is keyed by URI, guarded by a `Mutex`, and manually closed on credential changes (`papp/composeApp/src/commonMain/kotlin/xyz/lilsus/papp/data/nwc/
  NwcWalletRepositoryImpl.kt:35-107`). Errors are translated into domain errors by pattern-matching exception types.

  This mirrors what a general-purpose wallet app would do today: validate credentials, persist them, display discovery metadata, then pay invoices with retries and UI-friendly error messages.

  ## Friction & Opportunities
  ### 1. Connection lifecycle is manual and error-prone
  - papp must manage `CoroutineScope`, `Mutex`, caching, and teardown for every credential (`NwcWalletRepositoryImpl.kt:35-107`).
  - One-off flows (discovery) allocate ad-hoc scopes (`WalletDiscoveryRepositoryImpl.kt:41-44`) purely to satisfy `NwcClient.create`.
  - Opportunity: expose a first-class `NwcSession`/`NwcConnectionManager` that owns scope, relay runtime, and clean shutdown so apps can simply `session.use { payInvoice(...) }` or subscribe once per wallet.

  ### 2. URI parsing & credential representation leak into apps
  - Lack of a high-level `NwcUri` value type forces the app to re-parse strings to extract pubkeys (`domain/util/NwcUriUtils.kt`) and trim input everywhere.
  - Opportunity: return an immutable credential model with ready-to-use hex/pubkey forms, relay list, lud16, and helpers to (re)format URIs.

  ### 3. Wallet discovery duplicates handshake logic
  - The app recreates the “connect → fetch metadata → get info → map capabilities” flow (`WalletDiscoveryRepositoryImpl.kt:33-87`).
  - Opportunity: provide a `NwcDiscoveryService` that validates URIs, negotiates encryption, aggregates `WalletMetadata` + `GetInfoResult`, and surfaces structured capability data/alias suggestions.

  ### 4. Error signaling lacks structure
  - Clients rely on ad-hoc cause inspection (`isNetworkIOException()` in both repositories) to detect connectivity issues (`NwcWalletRepositoryImpl.kt:70-121`, `WalletDiscoveryRepositoryImpl.kt:66-105`).
  - `NwcRequestException` exposes raw error codes, but there is no sealed `NwcFailure` taxonomy to distinguish wallet rejections vs. protocol vs. network.
  - Opportunity: redesign exceptions/results so consumers get typed failures (`Network`, `Timeout`, `WalletError(code)`, `Protocol`, `EncryptionNegotiation`) without traversing cause chains.

  ### 5. Capabilities & metadata are stringly-typed
  - papp checks `methods`/`encryptionSchemes`/`notifications` by comparing strings (`WalletDiscoveryRepositoryImpl.kt:47-61`, `domain/model/WalletDiscovery.kt`).
  - Opportunity: ship enums/value classes (e.g., `Capability.PayInvoice`, `EncryptionScheme.Nip44V2`) and helper predicates to reduce copy/paste logic and future-proof additional commands.

  ### 6. Notifications & connection state visibility are limited
  - `NwcClient.notifications` is a `SharedFlow`, but there is no connection state, relay health, or auto-reconnect feedback exposed to apps.
  - General wallet UIs will want to show “connecting / disconnected / live” indicators, mute notifications per session, and recover from relay failures.
  - Opportunity: expose a `StateFlow<NwcConnectionState>` (connecting, ready, degraded), allow opting into structured notification streams (including unknown notification passthrough), and offer hooks for app-level logging.

  ### 7. Developer tooling & testing support is minimal
  - `NwcClient` is a concrete class without an interface/fake; consumers must hand-roll stubs for unit tests.
  - There is no embedded test harness for wallet simulators or fixture-driven responses, making it harder to exercise timeouts and error paths.
  - Opportunity: define an interface (or abstract base) with an in-memory fake, plus a lightweight mock wallet in the repo/samples.

  ### 8. Encryption negotiation is hard-coded
  - `refreshWalletMetadata` fails if `nip44_v2` is absent (`library/src/commonMain/kotlin/io/github/nostr/nwc/NwcClient.kt:229-238`). That is fine for papp, but other clients might want to degrade to NIP-04 when the wallet
  only offers that.
  - Opportunity: make the required/optional encryption schemes configurable during session creation and surface the negotiated scheme in the discovery result.

  ## Proposed Workstreams
  ### P0 — Ergonomic surface redesign
  - Introduce a `NwcUri` value class + parser that returns sanitized fields, hex strings, and helper methods. Replace ad-hoc parsing in `parseNwcUri` with this richer model.
  - Create a `NwcSession` (or `NwcConnection`) abstraction that hides scope/runtime management. Provide `NwcSessionManager` for caching by wallet key, scoped flows, and structured shutdown.
  - Provide a `discoverWallet(uri, requirements)` entry point that wraps connection bootstrap, metadata/info aggregation, and returns a typed `NwcWalletDescriptor` (capabilities, network, alias, color, negotiated encryption
  scheme).
  - Redesign error handling around `sealed class NwcFailure` with subtypes for `Timeout`, `Network`, `Wallet(code: NwcErrorCode)`, `Protocol`, `EncryptionUnsupported`, etc., plus convenience mapping from responses. Deprecate
  raw `RuntimeException` usage.
  - Add enums for capabilities, notifications, and encryption schemes with extension helpers (e.g., `descriptor.supports(Capability.PayInvoice)`), replacing string comparisons.

  ### P1 — Reliability & observability
  - Expose connection/relay state via `StateFlow<NwcConnectionState>` and per-session metrics (last ping, in-flight requests) so apps can render status and retry intelligently.
  - Implement automatic relay reconnection/backoff inside the session manager, surfacing degraded states instead of throwing immediately.
  - Allow pluggable interceptors (logging, metrics, tracing) on requests/responses and notifications.
  - Extend `walletMetadata` watching so the client refreshes when the replaceable info event changes (subscribe once and update the state flow).
  - Support per-request overrides (timeouts, expiration, metadata) through a request builder DSL to avoid duplicating optional parameters.

  ### P2 — Future-ready features & ecosystem support
  - Provide a full fake/mock wallet service (or harness) for integration tests and sample apps; include scripted responses for `pay_invoice`, `make_invoice`, `list_transactions`.
  - Publish a higher-level “wallet toolkit” around common flows: balance polling, transaction history paging, notification filtering, payment budgeting, etc., leveraging existing commands.
  - Add first-class support for multi-wallet apps: session pooling, per-wallet notification channels, and helpers to switch active sessions without reconstructing DI graphs.
  - Expand protocol coverage to handle upcoming NIP-47 extensions (e.g., budgets, enhanced notifications) by modeling reserved capabilities now.
  - Produce comprehensive documentation and samples (discover + connect + pay + notifications) to set expectations for library integrators.

  ## Cross-Repo Considerations
  - `nostr-kmp` may need new hooks to expose relay connection state, connection errors, and subscription lifecycle events so `nwc-kmp` can surface them via its session API.
  - Evaluate whether `RelaySessionSettings` should expose heartbeat/ping tuning required for long-lived wallet connections and if defaults need adjustment.
  - Consider contributing a shared error taxonomy (`NetworkError`, `ProtocolError`) to `nostr-kmp` so downstream libraries share classification logic.

  ## Suggested Next Steps
  1. Align on the target API surface (`NwcUri`, `NwcSession`, error model) and document breaking changes.
  2. Prototype the discovery helper (`discoverWallet`) and error taxonomy; dogfood it inside papp to validate ergonomics.
  3. Build the session manager with connection state flows and integrate it into papp’s `NwcWalletRepositoryImpl` as a drop-in replacement.
  4. Add capability/notification enums and migrate papp’s discovery logic to them to ensure backward compatibility with existing method names.
  5. Introduce test doubles and sample scripts, then document end-to-end flows (connect → discover → pay → notifications) in the library README.
  6. Iterate on observability (connection metrics, logging) and optional encryption negotiation before expanding to multi-wallet orchestration features.

  Let me know if you want this dropped into a file inside either repo or circulated elsewhere.

## Maintainer Review

### Feasibility Snapshot
- `NwcUri` value object + richer credentials: **low risk**. `parseNwcUri` already builds `NwcCredentials` (`library/src/commonMain/kotlin/io/github/nostr/nwc/NwcConnection.kt:15`); reshaping it into an immutable public model and adding helpers is mostly typing and call-site updates.
- Session/connection manager: **medium effort**. `NwcClient` already owns the runtime list (`NwcClient.kt:146-160`) and closes them (`NwcClient.kt:176-202`). Extracting that into a `NwcSession` wrapper with pooled scope ownership is tractable but requires reconsidering lifecycle entry points and coroutine supervision so we do not leak runtimes across cached sessions.
- Discovery helper: **medium effort**. We already fetch metadata and `getInfo` sequentially (`NwcClient.kt:204-268`, `NwcClient.kt:284-322`); wrapping that into a single `discoverWallet` entry point is straightforward once a session factory exists.
- Error taxonomy: **medium/high effort**. We can wrap current exceptions (`NwcExceptions.kt:5-15`), but mapping upstream failures cleanly depends on richer signals from `nostr-kmp` (connection errors are currently surfaced only as `RelaySessionOutput.Error`).
- Capability/notification enums: **low risk** if we preserve an `Unknown` bucket so future wallet feature flags do not break parsing.
- Connection state/observability: **medium/high effort**. We intentionally drop `RelaySessionOutput.ConnectionStateChanged` in `handleOutput` today (`NwcClient.kt:584-593`). Surfacing this as a `StateFlow<NwcConnectionState>` means modelling multi-relay state aggregation, reconnection policies, and deciding how to expose degraded vs. failed states without over-notifying consumers.
- Tooling & fakes: **medium effort**. Introducing an interface + fake for `NwcClient` or the proposed `NwcSession` is straightforward, but shipping a scripted wallet harness implies standing up signing/encryption scaffolding (nip44) and retaining event determinism.
- Encryption negotiation fallback: **policy trade-off**. Allowing `nip04` downgrade is easy technically (we already parse advertised schemes in `parseWalletMetadata` around `NwcClient.kt:724-745`), but defaulting to a weaker cipher is risky. Making it opt-in with clear telemetry feels acceptable.

### Trade-offs & Considerations
- A `NwcSession`/manager abstraction will likely invert the current constructor that insists on a caller-owned `CoroutineScope`. Supplying our own scope (e.g., `SupervisorJob + Dispatchers.Default`) raises ownership questions for hosted apps. Providing both modes (library-managed vs. caller-provided scope) would cover power users but complicate API docs.
- Consolidating discovery and metadata fetching implies we keep runtimes hot between calls. For short-lived flows, we should budget for graceful shutdown delays to avoid leaving sockets open unnecessarily.
- The typed error model should align with upstream `nostr-kmp` so network/protocol failures propagate consistently across projects. We may want to upstream an `EngineError` → `NwcFailure` mapping helper, or extend `nostr-kmp` with more granular reasons (timeouts vs. TLS failure vs. auth challenge).
- Enumerations for capabilities/notifications must remain forward-compatible. Prefer sealed interfaces with a fallback `Unknown(rawValue)` variant rather than closed enums that require a breaking release whenever the spec adds a flag.
- Emitting connection state flows invites demand for retry/backoff controls. We should expose configuration knobs (max reconnect attempts, jitter) and document power consumption implications for mobile apps that keep long-lived sessions.
- Introducing pluggable interceptors/logging is valuable but adds indirection for every request. We should design the pipeline so the zero-interceptor case remains allocation-light (e.g., inline functions or immutable handler chains).

### Dependencies on Upstream (`../nostr-kmp`)
- Better connection/error signalling likely needs additions in `nostr-runtime-coroutines`. Today we only get `RelaySessionOutput.Error` with a message string; exporting concrete error categories or stacks (e.g., TLS vs. DNS vs. websocket close code) would let `NwcFailure` be truly structured.
- Connection health flows would benefit from a first-class `StateFlow<ConnectionSnapshot>` in the runtime instead of reconstructing state from sporadic outputs. Exposing helper APIs to aggregate multi-relay health upstream could avoid duplicate logic downstream.
- If we standardise error taxonomies, consider sharing them between `nostr-kmp` and `nwc-kmp` to avoid divergent duplicates (e.g., a shared `NetworkFailure` sealed interface).
- Test harnesses may reuse the existing transport factories. Shipping an in-memory relay or a deterministic fake connection from `nostr-kmp` would dramatically reduce the effort of building a scripted wallet simulator here.
