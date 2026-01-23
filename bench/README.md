# Nostr SDK Bench Harness (local-only)

This directory contains a standalone benchmark harness used to compare SDK branches. It is a **separate Gradle build** and is not included in the root build, so it will not break when switching branches. Run it via `./gradlew -p bench ...`.

## Why a separate build?
- Keeps benchmarks out of the main build graph.
- Lets you keep both `bench-new` and `bench-legacy` side-by-side without forcing both to compile.
- You can run only the benchmark that matches the currently checked-out branch.

## Modules
- `bench-common`: scenario schema + metrics output models
- `relay-sim`: fake relay WebSocket server (scenario-driven)
- `bench-new`: benchmark runner for the new SDK
- `bench-legacy`: benchmark runner for the legacy SDK
- `analysis`: python comparison script for new vs legacy results

## Running
Start the relay simulator in one terminal:

```
./gradlew -p bench :relay-sim:run --args="--scenario scenarios/baseline.json"
```

Run the benchmark in another terminal:

```
./gradlew -p bench :bench-new:run --args="--scenario scenarios/baseline.json --results results/baseline-new.json"
```

Legacy branch runner:

```
./gradlew -p bench :bench-legacy:run --args="--scenario scenarios/baseline.json --results results/baseline-legacy.json"
```

Compare results:

```
python3 -m venv bench/analysis/.venv
source bench/analysis/.venv/bin/activate
python bench/analysis/compare.py --new bench/results/baseline-new.json --legacy bench/results/baseline-legacy.json
```

Repeat with other scenarios in `bench/scenarios/`:
- `baseline.json`
- `multi-sub.json`
- `edge-cases.json`
- `slow-relay.json`
- `nwc-pay.json` (Nostr Wallet Connect pay flow)

## Notes
- The relay simulator generates well-formed Nostr events but **does not compute real signatures**. This keeps the relay fast and deterministic; the SDK still exercises decoding, subscription handling, and backpressure.
- The relay simulator streams the configured plans for any subscription; optional forwarding can match simple REQ filters (kinds, authors, ids, tag filters).
- For stronger authenticity checks, the relay can be extended to sign events using `nostr-crypto` on each branch.
- `bench-new` writes a JSON report with per-phase throughput and lag statistics, plus publish and error metrics.

## NWC scenario knobs
The `nwc` block enables Nostr Wallet Connect pay request/response benchmarking:
- `requestCount`, `ratePerSec`, `timeoutMs`
- `encryption.nip04Ratio` (0.0 → all NIP-44, 1.0 → all NIP-04)
- `request.invoiceSize`, `request.amountMsat`
- `wallet.*` rates for error/drop/duplicate/wrongTag/invalidContent/unknownResponse plus response delay range

For NWC, set `relayBehavior.forward.enabled = true` and include kinds `23194` and `23195` so the relay forwards client-published events between the simulated wallet and client.

## Branch workflow
When you switch to the legacy branch:
- Keep `bench/` as-is.
- Run only the `bench-legacy` module.
- `bench-new` will stay present but unused (and may not compile on legacy).
