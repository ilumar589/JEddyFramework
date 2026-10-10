#!/usr/bin/env python3
"""Nightly JMH checks (TASKS M11-03).

1. Regressions: each benchmark's score against bench/baseline/<platform>.json (written from an
   earlier nightly with --write-baseline). A benchmark slower than the baseline by more than its
   threshold (default 25%, per benchmark in the baseline file) fails the run.
2. Allocation sweep (run JMH with -prof gc): benchmarks of per-frame paths must allocate nothing.
   JMH's gc.alloc.rate.norm sums the allocation of every thread in the fork, so background threads
   add a rate in bytes per millisecond that long operations pick up (the first nightly saw 3-29 B
   per ms of operation time, the same within each fork whatever the benchmark). A hot path fails
   above 16 B/op plus 64 B per ms of its operation time; the others are only reported. Exact
   per-thread checks are the @NoAllocation tests (Allocations.assertNoAllocation).

Usage: jmh-check.py RESULTS.json BASELINE.json [--write-baseline]"""
import json
import os
import re
import sys

# Hot paths: zero allocation per operation (DESIGN §4.2).
ZERO_ALLOC = [
    r"AudioBench\.", r"DrawBench\.cull", r"JobBench\.", r"MathBench\.", r"PhysicsBench\.",
    r"TransformBench\.", r"FfmBench\.(abs|mat4)", r"CollectionsBench\.(intIntMapGet|radixSort16k)",
    r"AssetBench\.(pakFind|vfsSize)", r"BaselineBench\.", r"AnimationBench\.",
]
NOISE = 16.0  # B/op
BACKGROUND = 64.0  # B per ms of operation time


def op_millis(r):
    """Time of one operation in ms, from the primary score (average time or throughput)."""
    score, unit = r["primaryMetric"]["score"], r["primaryMetric"]["scoreUnit"]
    per_op = {"ns/op": 1e-6, "us/op": 1e-3, "ms/op": 1.0, "s/op": 1e3}
    ops_per_ms = {"ops/ns": 1e6, "ops/us": 1e3, "ops/ms": 1.0, "ops/s": 1e-3}
    if unit in per_op:
        return score * per_op[unit]
    if unit in ops_per_ms and score > 0:
        return 1.0 / (score * ops_per_ms[unit])
    return 0.0

results_path, baseline_path = sys.argv[1], sys.argv[2]
write = "--write-baseline" in sys.argv
results = json.load(open(results_path))

def key(r):
    params = r.get("params") or {}
    return r["benchmark"].replace("dev.jeddy.bench.", "") + "".join(f"[{k}={v}]" for k, v in sorted(params.items()))

def lower_is_better(unit):
    return "/op" in unit and not unit.startswith("ops/")

scores = {key(r): (r["primaryMetric"]["score"], r["primaryMetric"]["scoreUnit"]) for r in results}

if write:
    base = {"thresholds": {"default": 0.25}, "scores": {k: {"score": s, "unit": u} for k, (s, u) in scores.items()}}
    os.makedirs(os.path.dirname(baseline_path) or ".", exist_ok=True)
    json.dump(base, open(baseline_path, "w"), indent=1, sort_keys=True)
    print(f"wrote {baseline_path} ({len(scores)} benchmarks)")

failures, notes = [], []
if os.path.isfile(baseline_path) and not write:
    base = json.load(open(baseline_path))
    default = base.get("thresholds", {}).get("default", 0.25)
    for k, (score, unit) in scores.items():
        b = base.get("scores", {}).get(k)
        if not b or b.get("unit") != unit or b["score"] <= 0:
            continue
        limit = base.get("thresholds", {}).get(k, default)
        change = (score - b["score"]) / b["score"]
        worse = change if lower_is_better(unit) else -change
        line = f"{k}: {score:.4g} {unit} vs {b['score']:.4g} ({change:+.0%})"
        if worse > limit:
            failures.append("regression " + line)
        elif abs(change) > limit:
            notes.append("improved " + line)
elif not write:
    notes.append(f"no baseline at {baseline_path}: regressions not checked")

for r in results:
    alloc = (r.get("secondaryMetrics") or {}).get("·gc.alloc.rate.norm") or (r.get("secondaryMetrics") or {}).get("gc.alloc.rate.norm")
    if alloc is None:
        continue
    k, bytes_per_op = key(r), alloc["score"]
    hot = any(re.search(p, k) for p in ZERO_ALLOC)
    ms = op_millis(r)
    allowed = NOISE + BACKGROUND * ms
    per_ms = f", {bytes_per_op / ms:.1f} B per ms of operation" if ms > 0 else ""
    if hot and bytes_per_op > allowed:
        failures.append(f"allocates {k}: {bytes_per_op:.1f} B/op (allowed {allowed:.0f}{per_ms})")
    elif hot and bytes_per_op > NOISE:
        notes.append(f"background allocation {k}: {bytes_per_op:.1f} B/op{per_ms}")
    elif bytes_per_op > NOISE:
        notes.append(f"allocation (allowed) {k}: {bytes_per_op:.1f} B/op{per_ms}")

for n in notes:
    print("note:", n)
if notes:
    print("::notice title=JMH nightly::" + "%0A".join(notes[:60]))
for f in failures:
    print("FAIL:", f)
if failures:
    print("::error title=JMH nightly::" + "%0A".join(failures[:60]))
    sys.exit(1)
print(f"{len(scores)} benchmarks checked, no regressions or hot-path allocations")
