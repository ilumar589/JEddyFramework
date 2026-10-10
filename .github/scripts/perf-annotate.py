#!/usr/bin/env python3
"""Posts the iso demo perf capture (perf.json, TASKS M10-05) as one GitHub notice annotation, with
a warning per failed budget, so the numbers are readable from the PR view and the checks API."""
import json
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "samples/iso-demo/build/perf/perf.json"
try:
    perf = json.load(open(path))
except (OSError, ValueError) as e:
    print(f"::warning title=Perf::no report ({e})")
    sys.exit(0)
lines = [f"{perf.get('seconds', 0):.0f} s, {perf.get('gameFrames', 0):.0f} game frames, GPU: {perf.get('gpu')}"]
for b in perf.get("budgets", []):
    verdict = {True: "pass", False: "FAIL", None: "n/a"}[b.get("pass")]
    lines.append(f"{b['budget']}: {b['measured']} (target {b['target']}) {verdict}")
    if b.get("pass") is False:
        print(f"::warning title=Perf budget::{b['budget']}: {b['measured']} (target {b['target']})")
halves = perf.get("allocatedBytesSecondHalfByThread")
if halves:
    lines.append("Second-half allocation by thread: " + ", ".join(f"{k} {v:.0f} B" for k, v in halves.items()))
if perf.get("allocationEvents"):
    lines.append("Allocating frames: " + perf["allocationEvents"])
print("::notice title=Perf capture::" + "%0A".join(lines))
