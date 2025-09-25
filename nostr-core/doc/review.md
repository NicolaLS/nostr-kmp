# nostr-core Review – April 27, 2026

This pass focuses solely on the `nostr-core` module, highlighting bugs or high-risk behaviours that could surprise integrators. Items are ordered by severity.

## Critical Findings

1. ~~**Offline unsubscribe resurrects subscriptions on reconnect**~~ _Resolved: reconnect now sends pending `CLOSE` frames instead of `REQ` and leaves closing subscriptions untouched (`nostr-core/src/commonMain/kotlin/session/RelaySessionReducer.kt:132-170`)._

2. ~~**Closed subscriptions re-subscribe after reconnect**~~ _Resolved: `handleConnectionOpened` skips `REQ` for `Closed` entries so relays aren’t re-engaged unexpectedly (`nostr-core/src/commonMain/kotlin/session/RelaySessionReducer.kt:132-170`)._

## High Severity

3. **`println` in production path**  
   `handleUnknown` logs directly to stdout (`nostr-core/src/commonMain/kotlin/session/RelaySessionReducer.kt:213-221`). In real deployments this can leak into UI logs or fail to integrate with logging backends. Prefer emitting a structured error via `RelaySessionOutput.Error` (already returned) or providing a pluggable logger.

4. **Filter tag validation is too strict for emerging NIPs**  
   `Filter` enforces `#<single-letter>` keys (`nostr-core/src/commonMain/kotlin/model/Filter.kt:39-46`). Several NIPs (e.g. replacements for `#t`, `#d`, multi-word tags) already use multi-character suffixes. This will reject otherwise valid filters and cause request failures on newer relays. Consider relaxing to permit longer suffixes or making the validator pluggable.

## Medium / Observational

5. **State growth is bounded but defaults may still be high for constrained clients**  
   `RelaySessionSettings` caps dedupe and publish histories at 200 entries; this is adequate for typical desktop clients but may be heavy for embedded usage. Document the tuning knobs (`maxEventReplayIds`, `maxPublishStatuses`).

6. **Optional canonical ID verification defaults to off**  
   The new `verifyEventIds` flag allows stricter replay handling (`nostr-core/src/commonMain/kotlin/session/RelaySessionReducer.kt:122-138`). Consider surfacing that flag prominently in docs so consumers who need strictness enable it.

## Suggested Fixes

- Skip or defer resubscribe logic for subscriptions in `Closing` or `Closed` status. For example, queue a `CLOSE` command when the transport reconnects, and only send `REQ` for entries still marked `Active`/`Pending`.
- Remove `println` diagnostics in favour of structured outputs/log hooks.
- Relax filter tag validation or expose an extension point for custom tag rules.

Addressing the two critical findings is the top priority: without them, unsubscribe semantics are unreliable and closed subscriptions can unexpectedly reappear.
