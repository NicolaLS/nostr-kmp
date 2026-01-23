#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


@dataclass
class PhaseSummary:
    name: str
    expected: Optional[int]
    received: int
    min_lag_ns: Optional[int]
    max_lag_ns: Optional[int]
    avg_lag_ns: Optional[float]


@dataclass
class SubscriptionSummary:
    id: str
    subscribe_at_ns: int
    live_at_ns: Optional[int]
    closed_at_ns: Optional[int]
    interrupted_at_ns: Optional[int]
    total_events: int
    dropped_events: int
    phases: List[PhaseSummary]


@dataclass
class PublishSummary:
    total: int
    accepted: int
    rejected: int
    timed_out: int
    min_latency_ns: Optional[int]
    max_latency_ns: Optional[int]
    avg_latency_ns: Optional[float]


@dataclass
class NwcEncryptionSummary:
    nip04_requests: int
    nip44_requests: int
    nip04_responses: int
    nip44_responses: int


@dataclass
class NwcSummary:
    total_requests: int
    sent_requests: int
    responses: int
    responses_ok: int
    responses_error: int
    responses_invalid: int
    responses_unmatched: int
    responses_duplicate: int
    timeouts: int
    min_latency_ns: Optional[int]
    max_latency_ns: Optional[int]
    avg_latency_ns: Optional[float]
    encryption: NwcEncryptionSummary


@dataclass
class ResultSummary:
    name: str
    started_ms: int
    finished_ms: int
    connect_ms: Optional[int]
    subscriptions: List[SubscriptionSummary]
    publish: Optional[PublishSummary]
    nwc: Optional[NwcSummary]
    parse_errors: int
    send_failures: int
    backpressure_drops: int
    input_drops: int


@dataclass
class AggregateStats:
    duration_ms: int
    total_events: int
    dropped_events: int
    throughput_eps: float
    lag_min_ns: Optional[int]
    lag_max_ns: Optional[int]
    lag_avg_ns: Optional[float]
    phase_coverage: Dict[str, Tuple[Optional[int], int]]


def load_result(path: Path) -> ResultSummary:
    data = json.loads(path.read_text())

    subs: List[SubscriptionSummary] = []
    for sub in data.get("subscriptions", []):
        phases = [
            PhaseSummary(
                name=phase["name"],
                expected=phase.get("expectedCount"),
                received=phase.get("receivedCount", 0),
                min_lag_ns=phase.get("minLagNs"),
                max_lag_ns=phase.get("maxLagNs"),
                avg_lag_ns=phase.get("avgLagNs"),
            )
            for phase in sub.get("phases", [])
        ]
        subs.append(
            SubscriptionSummary(
                id=sub["id"],
                subscribe_at_ns=sub["subscribeAtNs"],
                live_at_ns=sub.get("liveAtNs"),
                closed_at_ns=sub.get("closedAtNs"),
                interrupted_at_ns=sub.get("interruptedAtNs"),
                total_events=sub.get("totalEvents", 0),
                dropped_events=sub.get("droppedEvents", 0),
                phases=phases,
            )
        )

    publish = None
    if data.get("publish") is not None:
        pub = data["publish"]
        publish = PublishSummary(
            total=pub.get("total", 0),
            accepted=pub.get("accepted", 0),
            rejected=pub.get("rejected", 0),
            timed_out=pub.get("timedOut", 0),
            min_latency_ns=pub.get("minLatencyNs"),
            max_latency_ns=pub.get("maxLatencyNs"),
            avg_latency_ns=pub.get("avgLatencyNs"),
        )

    nwc = None
    if data.get("nwc") is not None:
        nwc_raw = data["nwc"]
        enc = nwc_raw.get("encryption", {}) if isinstance(nwc_raw, dict) else {}
        nwc = NwcSummary(
            total_requests=nwc_raw.get("totalRequests", 0),
            sent_requests=nwc_raw.get("sentRequests", 0),
            responses=nwc_raw.get("responses", 0),
            responses_ok=nwc_raw.get("responsesOk", 0),
            responses_error=nwc_raw.get("responsesError", 0),
            responses_invalid=nwc_raw.get("responsesInvalid", 0),
            responses_unmatched=nwc_raw.get("responsesUnmatched", 0),
            responses_duplicate=nwc_raw.get("responsesDuplicate", 0),
            timeouts=nwc_raw.get("timeouts", 0),
            min_latency_ns=nwc_raw.get("minLatencyNs"),
            max_latency_ns=nwc_raw.get("maxLatencyNs"),
            avg_latency_ns=nwc_raw.get("avgLatencyNs"),
            encryption=NwcEncryptionSummary(
                nip04_requests=enc.get("nip04Requests", 0),
                nip44_requests=enc.get("nip44Requests", 0),
                nip04_responses=enc.get("nip04Responses", 0),
                nip44_responses=enc.get("nip44Responses", 0),
            ),
        )

    connection = data.get("connection", {})
    connect_ms = connection.get("connectDurationMs")

    errors = data.get("errors", {})

    return ResultSummary(
        name=data.get("scenario", path.stem),
        started_ms=data.get("startedAtEpochMs", 0),
        finished_ms=data.get("finishedAtEpochMs", 0),
        connect_ms=connect_ms,
        subscriptions=subs,
        publish=publish,
        nwc=nwc,
        parse_errors=errors.get("parseErrors", 0),
        send_failures=errors.get("sendFailures", 0),
        backpressure_drops=errors.get("backpressureDrops", 0),
        input_drops=errors.get("inputDrops", 0),
    )


def aggregate(summary: ResultSummary) -> AggregateStats:
    duration_ms = max(0, summary.finished_ms - summary.started_ms)
    total_events = sum(s.total_events for s in summary.subscriptions)
    dropped_events = sum(s.dropped_events for s in summary.subscriptions)
    throughput_eps = (total_events / (duration_ms / 1000.0)) if duration_ms > 0 else 0.0

    lag_min: Optional[int] = None
    lag_max: Optional[int] = None
    lag_sum = 0.0
    lag_count = 0

    phase_coverage: Dict[str, Tuple[Optional[int], int]] = {}

    for sub in summary.subscriptions:
        for phase in sub.phases:
            expected, received = phase.expected, phase.received
            if phase.name not in phase_coverage:
                phase_coverage[phase.name] = (expected, received)
            else:
                prev_expected, prev_received = phase_coverage[phase.name]
                combined_expected = None
                if prev_expected is not None or expected is not None:
                    combined_expected = (prev_expected or 0) + (expected or 0)
                phase_coverage[phase.name] = (combined_expected, prev_received + received)

            if phase.min_lag_ns is not None:
                lag_min = phase.min_lag_ns if lag_min is None else min(lag_min, phase.min_lag_ns)
            if phase.max_lag_ns is not None:
                lag_max = phase.max_lag_ns if lag_max is None else max(lag_max, phase.max_lag_ns)
            if phase.avg_lag_ns is not None and phase.received > 0:
                lag_sum += phase.avg_lag_ns * phase.received
                lag_count += phase.received

    lag_avg = (lag_sum / lag_count) if lag_count > 0 else None

    return AggregateStats(
        duration_ms=duration_ms,
        total_events=total_events,
        dropped_events=dropped_events,
        throughput_eps=throughput_eps,
        lag_min_ns=lag_min,
        lag_max_ns=lag_max,
        lag_avg_ns=lag_avg,
        phase_coverage=phase_coverage,
    )


def fmt_ns(value: Optional[float]) -> str:
    if value is None:
        return "-"
    if value < 1_000:
        return f"{value:.0f} ns"
    if value < 1_000_000:
        return f"{value/1_000:.2f} us"
    if value < 1_000_000_000:
        return f"{value/1_000_000:.2f} ms"
    return f"{value/1_000_000_000:.2f} s"


def fmt_pct(delta: float) -> str:
    if math.isnan(delta):
        return "-"
    sign = "+" if delta >= 0 else ""
    return f"{sign}{delta:.1f}%"


def delta_pct(new: Optional[float], old: Optional[float]) -> float:
    if new is None or old is None or old == 0:
        return float("nan")
    return ((new - old) / old) * 100.0


def print_header(title: str) -> None:
    print("\n" + title)
    print("-" * len(title))


def render_table(headers: List[str], rows: List[List[str]]) -> None:
    widths = [len(h) for h in headers]
    for row in rows:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], len(cell))

    def fmt_row(values: List[str]) -> str:
        return "  ".join(value.ljust(widths[i]) for i, value in enumerate(values))

    print(fmt_row(headers))
    print(fmt_row(["-" * w for w in widths]))
    for row in rows:
        print(fmt_row(row))


def compare(new: ResultSummary, legacy: ResultSummary) -> None:
    new_agg = aggregate(new)
    legacy_agg = aggregate(legacy)

    print_header("Overview")
    rows = [
        [
            "duration",
            f"{new_agg.duration_ms} ms",
            f"{legacy_agg.duration_ms} ms",
            fmt_pct(delta_pct(new_agg.duration_ms, legacy_agg.duration_ms)),
        ],
        [
            "total events",
            str(new_agg.total_events),
            str(legacy_agg.total_events),
            fmt_pct(delta_pct(new_agg.total_events, legacy_agg.total_events)),
        ],
        [
            "throughput (events/s)",
            f"{new_agg.throughput_eps:.1f}",
            f"{legacy_agg.throughput_eps:.1f}",
            fmt_pct(delta_pct(new_agg.throughput_eps, legacy_agg.throughput_eps)),
        ],
        [
            "dropped events",
            str(new_agg.dropped_events),
            str(legacy_agg.dropped_events),
            fmt_pct(delta_pct(new_agg.dropped_events, legacy_agg.dropped_events)),
        ],
    ]
    render_table(["metric", "new", "legacy", "delta"], rows)

    print_header("Connection")
    rows = [
        [
            "connect duration",
            f"{new.connect_ms or '-'} ms",
            f"{legacy.connect_ms or '-'} ms",
            fmt_pct(delta_pct(new.connect_ms, legacy.connect_ms)),
        ]
    ]
    render_table(["metric", "new", "legacy", "delta"], rows)

    print_header("Latency")
    rows = [
        ["min lag", fmt_ns(new_agg.lag_min_ns), fmt_ns(legacy_agg.lag_min_ns), fmt_pct(delta_pct(new_agg.lag_min_ns, legacy_agg.lag_min_ns))],
        ["avg lag", fmt_ns(new_agg.lag_avg_ns), fmt_ns(legacy_agg.lag_avg_ns), fmt_pct(delta_pct(new_agg.lag_avg_ns, legacy_agg.lag_avg_ns))],
        ["max lag", fmt_ns(new_agg.lag_max_ns), fmt_ns(legacy_agg.lag_max_ns), fmt_pct(delta_pct(new_agg.lag_max_ns, legacy_agg.lag_max_ns))],
    ]
    render_table(["metric", "new", "legacy", "delta"], rows)

    if new.publish or legacy.publish:
        print_header("Publish")
        rows = []
        if new.publish:
            rows.append([
                "accepted",
                f"{new.publish.accepted}/{new.publish.total}",
                f"{legacy.publish.accepted}/{legacy.publish.total}" if legacy.publish else "-",
                fmt_pct(delta_pct(new.publish.accepted, legacy.publish.accepted if legacy.publish else None)),
            ])
            rows.append([
                "rejected",
                f"{new.publish.rejected}/{new.publish.total}",
                f"{legacy.publish.rejected}/{legacy.publish.total}" if legacy.publish else "-",
                fmt_pct(delta_pct(new.publish.rejected, legacy.publish.rejected if legacy.publish else None)),
            ])
            rows.append([
                "timed out",
                f"{new.publish.timed_out}/{new.publish.total}",
                f"{legacy.publish.timed_out}/{legacy.publish.total}" if legacy.publish else "-",
                fmt_pct(delta_pct(new.publish.timed_out, legacy.publish.timed_out if legacy.publish else None)),
            ])
            rows.append([
                "avg latency",
                fmt_ns(new.publish.avg_latency_ns),
                fmt_ns(legacy.publish.avg_latency_ns if legacy.publish else None),
                fmt_pct(delta_pct(new.publish.avg_latency_ns, legacy.publish.avg_latency_ns if legacy.publish else None)),
            ])
        render_table(["metric", "new", "legacy", "delta"], rows)

    if new.nwc or legacy.nwc:
        print_header("NWC")
        rows = [
            [
                "requests",
                str(new.nwc.total_requests if new.nwc else "-"),
                str(legacy.nwc.total_requests if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.total_requests if new.nwc else None, legacy.nwc.total_requests if legacy.nwc else None)),
            ],
            [
                "responses",
                str(new.nwc.responses if new.nwc else "-"),
                str(legacy.nwc.responses if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.responses if new.nwc else None, legacy.nwc.responses if legacy.nwc else None)),
            ],
            [
                "ok responses",
                str(new.nwc.responses_ok if new.nwc else "-"),
                str(legacy.nwc.responses_ok if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.responses_ok if new.nwc else None, legacy.nwc.responses_ok if legacy.nwc else None)),
            ],
            [
                "error responses",
                str(new.nwc.responses_error if new.nwc else "-"),
                str(legacy.nwc.responses_error if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.responses_error if new.nwc else None, legacy.nwc.responses_error if legacy.nwc else None)),
            ],
            [
                "timeouts",
                str(new.nwc.timeouts if new.nwc else "-"),
                str(legacy.nwc.timeouts if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.timeouts if new.nwc else None, legacy.nwc.timeouts if legacy.nwc else None)),
            ],
            [
                "invalid responses",
                str(new.nwc.responses_invalid if new.nwc else "-"),
                str(legacy.nwc.responses_invalid if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.responses_invalid if new.nwc else None, legacy.nwc.responses_invalid if legacy.nwc else None)),
            ],
            [
                "unmatched responses",
                str(new.nwc.responses_unmatched if new.nwc else "-"),
                str(legacy.nwc.responses_unmatched if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.responses_unmatched if new.nwc else None, legacy.nwc.responses_unmatched if legacy.nwc else None)),
            ],
            [
                "duplicate responses",
                str(new.nwc.responses_duplicate if new.nwc else "-"),
                str(legacy.nwc.responses_duplicate if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.responses_duplicate if new.nwc else None, legacy.nwc.responses_duplicate if legacy.nwc else None)),
            ],
            [
                "avg latency",
                fmt_ns(new.nwc.avg_latency_ns if new.nwc else None),
                fmt_ns(legacy.nwc.avg_latency_ns if legacy.nwc else None),
                fmt_pct(delta_pct(new.nwc.avg_latency_ns if new.nwc else None, legacy.nwc.avg_latency_ns if legacy.nwc else None)),
            ],
        ]
        render_table(["metric", "new", "legacy", "delta"], rows)

        print_header("NWC Encryption")
        rows = [
            [
                "nip04 requests",
                str(new.nwc.encryption.nip04_requests if new.nwc else "-"),
                str(legacy.nwc.encryption.nip04_requests if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.encryption.nip04_requests if new.nwc else None, legacy.nwc.encryption.nip04_requests if legacy.nwc else None)),
            ],
            [
                "nip44 requests",
                str(new.nwc.encryption.nip44_requests if new.nwc else "-"),
                str(legacy.nwc.encryption.nip44_requests if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.encryption.nip44_requests if new.nwc else None, legacy.nwc.encryption.nip44_requests if legacy.nwc else None)),
            ],
            [
                "nip04 responses",
                str(new.nwc.encryption.nip04_responses if new.nwc else "-"),
                str(legacy.nwc.encryption.nip04_responses if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.encryption.nip04_responses if new.nwc else None, legacy.nwc.encryption.nip04_responses if legacy.nwc else None)),
            ],
            [
                "nip44 responses",
                str(new.nwc.encryption.nip44_responses if new.nwc else "-"),
                str(legacy.nwc.encryption.nip44_responses if legacy.nwc else "-"),
                fmt_pct(delta_pct(new.nwc.encryption.nip44_responses if new.nwc else None, legacy.nwc.encryption.nip44_responses if legacy.nwc else None)),
            ],
        ]
        render_table(["metric", "new", "legacy", "delta"], rows)

    print_header("Errors")
    rows = [
        ["parse errors", str(new.parse_errors), str(legacy.parse_errors), fmt_pct(delta_pct(new.parse_errors, legacy.parse_errors))],
        ["send failures", str(new.send_failures), str(legacy.send_failures), fmt_pct(delta_pct(new.send_failures, legacy.send_failures))],
        ["backpressure drops", str(new.backpressure_drops), str(legacy.backpressure_drops), fmt_pct(delta_pct(new.backpressure_drops, legacy.backpressure_drops))],
        ["input drops", str(new.input_drops), str(legacy.input_drops), fmt_pct(delta_pct(new.input_drops, legacy.input_drops))],
    ]
    render_table(["metric", "new", "legacy", "delta"], rows)

    print_header("Phase Coverage")
    phase_names = sorted(set(new_agg.phase_coverage.keys()) | set(legacy_agg.phase_coverage.keys()))
    rows = []
    for name in phase_names:
        new_expected, new_received = new_agg.phase_coverage.get(name, (None, 0))
        legacy_expected, legacy_received = legacy_agg.phase_coverage.get(name, (None, 0))
        new_cov = (new_received / new_expected * 100.0) if new_expected else None
        legacy_cov = (legacy_received / legacy_expected * 100.0) if legacy_expected else None
        rows.append([
            name,
            f"{new_received}/{new_expected or '-'}",
            f"{legacy_received}/{legacy_expected or '-'}",
            f"{new_cov:.1f}%" if new_cov is not None else "-",
            f"{legacy_cov:.1f}%" if legacy_cov is not None else "-",
        ])
    render_table(["phase", "new", "legacy", "new coverage", "legacy coverage"], rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare legacy vs new bench results")
    parser.add_argument("--new", required=True, help="Path to new results JSON")
    parser.add_argument("--legacy", required=True, help="Path to legacy results JSON")
    args = parser.parse_args()

    new_path = Path(args.new)
    legacy_path = Path(args.legacy)

    new_result = load_result(new_path)
    legacy_result = load_result(legacy_path)

    compare(new_result, legacy_result)


if __name__ == "__main__":
    main()
