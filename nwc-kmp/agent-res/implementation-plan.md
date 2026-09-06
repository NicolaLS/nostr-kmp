# NWC-KMP & Nostr SDK Implementation Plan

Purpose: combine the client feedback and maintainer review into an iterative roadmap that delivers ergonomic improvements to `nwc-kmp` while coordinating the required upstream work in `../nostr-kmp`. Each step is scoped for small, reviewable commits.

## Phase 0 — Planning & Alignment
- [ ] **P0.1** Review design doc with stakeholders; agree that breaking changes are acceptable for this pre-release version.
- [ ] **P0.2** Draft API sketches for `NwcUri`, `NwcSession`, `NwcFailure`, and a minimal connection state model; circulate for sign-off before coding.
- [ ] **P0.3** Confirm upstream owners are willing to accept structured error and telemetry changes in `nostr-kmp`.

## Phase 1 — Credential Foundations
- [x] **P1.1** Refactor `parseNwcUri` to return an internal data class with immutable fields (no behaviour change).
- [x] **P1.2** Introduce public `NwcUri`/`NwcCredentials` value types with helpers for formatted URI output, hex/pubkey access, and relay lists (`NwcConnection.kt`, callers).
- [x] **P1.3** Update library entry points (`NwcClient.create`, tests) to accept the new types; provide extension functions for backward-compatibility.
- **Verification:** run existing unit tests; add focused tests for percent decoding and optional parameters. Backward compatibility is not a constraint.

## Phase 2 — Session Lifecycle Abstraction
- [x] **P2.1** Extract relay runtime bookkeeping from `NwcClient.initialize`/`close` into an internal `NwcSessionRuntime` helper (no public API yet).
- [x] **P2.2** Implement public `NwcSession` with lifecycle methods (`open`, `close`, `use`), owning its coroutine scope by default but accepting an optional external scope.
- [x] **P2.3** Add `NwcSessionManager` that caches sessions per credential and exposes `get(uri)`/`release(uri)`; ensure thread-safety via `Mutex`.
- [x] **P2.4** Update `NwcClient.create` to build on top of `NwcSession`, keeping old factory methods deprecated but functional.
- **Verification:** add tests covering session caching and shutdown; instrument logging for session leaks during development. Treat breaking changes as acceptable.

## Phase 3 — Discovery & Metadata Ergonomics
- [x] **P3.1** Implement typed models for wallet capabilities, notifications, and encryption schemes (`Capability`, `NotificationType`, `EncryptionScheme` with `Unknown` variants).
- [x] **P3.2** Create `NwcWalletDescriptor` aggregating `WalletMetadata`, `GetInfoResult`, negotiated encryption, and alias/network data.
- [x] **P3.3** Add `discoverWallet(uri, requirements)` API that uses a scoped `NwcSession` to refresh metadata, run `getInfo`, and build the descriptor.
- [x] **P3.4** Update sample code / docs to demonstrate discovery flow; ensure the descriptor round-trips capability enums.
- **Verification:** unit tests validating discovery when info event missing/late; integration test stub (can share session fake once available). Optimize for implementation speed over compatibility.

## Phase 4 — Structured Error Model
- [x] **P4.1** Define `sealed class NwcFailure` (Network, Timeout, Protocol, WalletError(code/message), EncryptionUnsupported, Unknown) plus mapping helpers.
- [x] **P4.2** Replace direct throws of `NwcException` in public APIs with `Result<NwcResponse>` or `Either`-style methods as appropriate; keep exception-based methods for backward-compatibility (deprecated).
- [x] **P4.3** Plumb network/protocol errors from `NwcClient` internals into `NwcFailure` using current `EngineError` data; log where information is insufficient pending upstream changes.
- [x] **P4.4** Update documentation and migration guide for new error handling.
- **Verification:** targeted tests for each failure variant; ensure wallet error codes surface intact. Backward compatibility intentionally ignored.

## Phase 5 — Connection State & Observability
- [x] **P5.1** Extend `NwcSession` to expose `StateFlow<NwcConnectionState>` aggregating per-relay states (Connecting, Ready, Degraded, Failed) using existing `RelaySessionOutput` events.
- [x] **P5.2** Add request/notification interceptors interface allowing logging/metrics; ensure zero-interceptor case stays allocation-light.
- [x] **P5.3** Introduce configurable retry/backoff settings and document defaults; consider power impact for mobile clients.
- [x] **P5.4** Surface `walletMetadata` refresh updates via a `StateFlow` that re-emits when replaceable events change.
- **Verification:** add unit tests simulating relay disconnects (via fake runtime once available); manual smoke test with sample app showing connection badges. No compatibility requirements.

## Phase 6 — Tooling, Tests, and Samples
- [x] **P6.1** Define `NwcClientContract` interface (subset of public API) and provide an in-memory fake for consumer unit tests.
- [x] **P6.2** Build a scripted wallet harness (initially in test fixtures) capable of responding to `pay_invoice`, `make_invoice`, and `list_transactions` for integration tests.
- [x] **P6.3** Expand README/docs with end-to-end flow using the new abstractions (`discover → connect → pay → notifications`).
- **Verification:** run fake-backed integration tests in CI; ensure documentation references are accurate. Compatibility not considered.

## Phase 7 — Upstream (`../nostr-kmp`) Enhancements
- [x] **P7.1** Add richer connection telemetry to `CoroutineNostrRuntime`: expose `StateFlow<ConnectionSnapshot>`, last failure, and reconnect attempt counters.
- [x] **P7.2** Extend `EngineError.ConnectionFailure` to include structured reasons, close codes, and optional causal exceptions; keep string fields temporarily for compatibility.
- [x] **P7.3** Provide lifecycle helpers (`RelaySessionManager`) so downstream clients can avoid duplicating runtime setup.
- [x] **P7.4** Introduce interceptor hooks for inbound/outbound frames and subscription lifecycle events.
- [ ] **P7.5** Ship loopback relay/testing utilities and document usage for downstream libraries. (IGNORE FOR NOW)
- **Verification:** add regression tests for new telemetry; document API shifts; compatibility is optional pre-release.

## Phase 8 — Integration & Cleanup
- [x] **P8.1** Refactor `nwc-kmp` to consume the new upstream telemetry/error APIs; remove temporary shim logic.
- [x] **P8.2** Revisit `NwcFailure` mappings to use structured data (drop message parsing).
- [x] **P8.3** Update session/retry logic to rely on upstream lifecycle helpers, simplifying local implementations.
- [x] **P8.4** Finalise documentation, deprecate legacy paths, and tag a release candidate.

## Iteration Guidelines
- Keep each bullet as one or two commits max (code + tests/docs).
- For Cross-repo changes: land upstream patches first, release snapshots, then update `nwc-kmp` dependency references.
- Maintain a changelog capturing breaking API adjustments and migration hints.
- Run unit + integration tests at each phase; add Kotlin Multiplatform targets (JVM/iOS) to CI once session abstractions stabilise.
