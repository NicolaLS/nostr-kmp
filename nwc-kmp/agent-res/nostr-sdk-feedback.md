# Nostr SDK Feedback for NWC-KMP Integration

## Context
- Reviewed the NIP-47 specification and the downstream friction report from the payment app.
- Walked through `nwc-kmp`’s usage of the coroutine runtime (`CoroutineNostrRuntime`) and reducer APIs inside `../nostr-kmp`.
- Goal: highlight upstream SDK adjustments that either unlock the requested library changes (session manager, structured failures, observability) or reduce integration friction for future apps.

## Alignment With Client Requests
- **Connection lifecycle APIs:** A higher-level `NwcSession` depends on richer connection snapshots than the raw `RelaySessionOutput` stream we observe in `NwcClient.kt`. Exposing a consolidated `StateFlow<ConnectionSnapshot>` (or a helper to aggregate multi-relay states) in the SDK would let us surface “connecting/degraded/ready” without reimplementing reducer bookkeeping.
- **Error taxonomy:** The proposed `sealed class NwcFailure` needs source-of-truth signals. Right now the runtime emits `RelaySessionOutput.Error(EngineError.ConnectionFailure|ProtocolViolation|OutboundFailure)` with string messages (see `nostr-core/src/commonMain/kotlin/session/RelaySessionOutput.kt`). Upstream can provide richer error payloads (cause, close code, transport error kind) so we avoid lossy message parsing downstream.
- **Capability modeling:** Enumerations for wallet capabilities will be less brittle if `nostr-kmp` also offers typed wrappers around the info-event content and `notifications` tag. A shared representation would prevent the same string parsing from being duplicated in both libraries.
- **Observability hooks:** Auto-reconnect/backoff for wallet sessions implies knowing when the runtime is attempting reconnects and when it gives up. Providing callback/flow hooks in `CoroutineNostrRuntime` for reconnect attempts or throttling decisions would make the requested `NwcConnectionState` possible without invasive tracking.

## Additional Upstream Opportunities

### 1. Richer Connection Telemetry
- **Observation:** `CoroutineNostrRuntime` maintains full reducer state internally but only emits discrete outputs (`nostr-runtime-coroutines/src/commonMain/kotlin/CoroutineNostrRuntime.kt:24-116`). Consumers must inspect every `RelaySessionOutput` to infer state transitions.
- **Suggestion:** Expose `runtime.connectionSnapshots: StateFlow<ConnectionSnapshot>` (wrapping `engine.state.connection`) and a helper to compute aggregate health across relays. Consider emitting reconnect attempt counters and last failure causes.
- **Trade-offs:** Slightly higher churn in the runtime API and the need to manage back-pressure on state updates, but minimal runtime overhead because the state is already tracked.

### 2. Structured Error Surfaces
- **Observation:** `EngineError.ConnectionFailure` carries only `url` and a message string (`nostr-core/src/commonMain/kotlin/session/RelaySessionOutput.kt:52-56`). We lose the original exception type, websocket close codes, or TLS errors.
- **Suggestion:** Introduce an extensible error model (e.g., `EngineError.ConnectionFailure(reason: ConnectionFailureReason, cause: Throwable?)`) and expose transport-layer metadata (close code, HTTP status). Downstream we can map directly to `NwcFailure.Network.Timeout`, `NwcFailure.Network.Unreachable`, etc.
- **Trade-offs:** API break for consumers already pattern-matching on `EngineError`. Mitigate by keeping string fields for one release and deprecating later.

### 3. Runtime Lifecycle Helpers
- **Observation:** Every consumer must juggle `CoroutineScope`, `RelayConnectionFactory`, and state collection manually. `NwcClient.initialize` mirrors this boilerplate for each relay (`library/src/commonMain/kotlin/io/github/nostr/nwc/NwcClient.kt:496-517`).
- **Suggestion:** Provide a builder/manager upstream (e.g., `RelaySessionManager`) that:
  - Owns the coroutine scope (or can spawn supervised child scopes).
  - Offers `connect(url)`, `subscribe(filters)`, and `observeConnection()` convenience wrappers.
  - Supports sharing a single runtime across multiple logical subscriptions with automatic reference counting.
- **Trade-offs:** Adds abstraction layering, but concentrates lifecycle correctness in one place and benefits any consumer that currently re-implements the same ceremony.

### 4. Pluggable Interceptors & Metrics
- **Observation:** There is no place to plug logging or metrics into request/response handling without wrapping every call site. The reducer already funnels outbound/inbound frames (`RelaySessionCommand.SendToRelay`, `RelaySessionIntent.RelayEvent`).
- **Suggestion:** Expose optional interceptors (or simple callbacks) at the runtime level for: frame send/receive, subscription registration, publish acknowledgement, and connection transitions. This would make it trivial for `nwc-kmp` to provide structured logging and latency metrics out of the box.
- **Trade-offs:** Need to keep the hot path allocation-light—prefer immutable handler lists called inline—otherwise high-volume relays may suffer.

### 5. Testing & Simulation Utilities
- **Observation:** Writing a fake relay/wallet today requires custom websocket servers. There is no in-memory transport adapter in the SDK.
- **Suggestion:** Ship a `LoopbackRelayConnectionFactory` (pairing two runtimes in memory) and a scripted wallet harness that can emit NIP-47 info/request/response events. This directly supports the library’s request for better developer tooling and enables sample apps without standing up infrastructure.
- **Trade-offs:** Maintenance cost for simulators, but confined to a test fixture module. Provides immediate value to every downstream consumer’s CI.

### 6. Subscription Ergonomics
- **Observation:** Clients repeat the pattern `connect → subscribe → wait for EOSE`. We also have to manage subscription IDs manually.
- **Suggestion:** Provide helper APIs such as `runtime.useSubscription(filters) { eventFlow -> ... }` that handle ID generation, auto-unsubscribe, and `EndOfStoredEvents` signaling.
- **Trade-offs:** Requires coroutine context awareness (suspending lambda) and must be careful not to hide backpressure handling, but it sharply reduces boilerplate in app code.

## Suggested Next Steps
1. Prototype richer error payloads in `nostr-core` and `nostr-runtime-coroutines`; validate that `nwc-kmp` can map them into a `sealed class NwcFailure` without string parsing.
2. Add connection snapshot/state flows to the runtime and dogfood them by exposing a basic connection status API in `nwc-kmp`.
3. Design a minimal `RelaySessionManager` upstream; once stable, refactor `NwcClient` on top of it to unlock the requested session ergonomics.
4. Stand up a small test/fixture module in the SDK with a loopback relay and scripted wallet so downstream libraries gain immediate testing tools.
5. Document the new hooks (connection telemetry, interceptors, helpers) alongside examples so that external integrators can adopt them without reverse-engineering `nwc-kmp`.
