#!/usr/bin/env python3
"""Stacks of the allocations in the second half of a perf capture (DESIGN §13.3), from a JFR
recording of TLAB events taken with small TLABs (-XX:TLABSize=256 -XX:MinTLABSize=256
-XX:-ResizeTLAB, settings .github/jfr/alloc.jfc), on the game, main and job worker threads.
Posts the heaviest stacks as a GitHub annotation.

Usage: alloc-stacks.py PERF_JSON RECORDING.jfr"""
import collections
import datetime
import json
import os
import re
import subprocess
import sys

perf_path, jfr_path = sys.argv[1], sys.argv[2]
try:
    perf = json.load(open(perf_path))
except (OSError, ValueError) as e:
    print(f"::notice title=Allocation stacks::no perf report ({e})")
    sys.exit(0)
if not os.path.isfile(jfr_path):
    print(f"::notice title=Allocation stacks::no recording at {jfr_path}")
    sys.exit(0)
start, end = perf["epochStartMs"], perf["epochEndMs"]
mid = (start + end) / 2
end -= 100  # the capture's own report starts at the window's end
jfr = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "jfr")
if not os.path.isfile(jfr) and not os.path.isfile(jfr + ".exe"):
    jfr = "jfr"
text = subprocess.run(
    [jfr, "print", "--events", "jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB",
     "--stack-depth", "40", jfr_path], capture_output=True, text=True).stdout
threads = re.compile(r"^(jeddy-game|main|jeddy-worker-\d+)$")
agg, sizes, first = collections.Counter(), collections.Counter(), {}
for event in re.split(r"jdk\.ObjectAllocation(?:InNewTLAB|OutsideTLAB) \{", text)[1:]:
    t = re.search(r"startTime = (\d+):(\d+):(\d+)\.(\d+) \((\d+)-(\d+)-(\d+)\)", event)
    th = re.search(r'eventThread = "([^"]+)"', event)
    if not t or not th or not threads.match(th.group(1)):
        continue
    h, mi, s, ms, y, mo, d = (int(x) for x in t.groups())
    when = datetime.datetime(y, mo, d, h, mi, s, ms * 1000).astimezone().timestamp() * 1000
    if not (mid <= when <= end):
        continue
    frames = re.findall(r"^\s+([\w.$<>/]+)\(.*?\) line: (\d+)", event, re.M)
    stack = " < ".join(f"{f}:{n}" for f, n in frames[:12]) or "?"
    size = re.search(r"(?:tlabSize|allocationSize) = ([\d.]+) (\w+)", event)
    bytes_ = float(size.group(1)) * {"bytes": 1, "byte": 1, "kB": 1024, "MB": 1048576}.get(size.group(2), 1) if size else 0
    key = (th.group(1), stack)
    agg[key] += 1
    sizes[key] += bytes_
    first.setdefault(key, datetime.datetime.fromtimestamp(when / 1000).strftime("%H:%M:%S"))
lines = [f"{sizes[k]:.0f} B in {n} events on {k[0]} from {first[k]}: {k[1]}" for k, n in
         sorted(agg.items(), key=lambda kv: -sizes[kv[0]])[:12]]
summary = f"{sum(agg.values())} allocation events on the game, main and worker threads in the second half"
print(summary)
for line in lines:
    print("  " + line)
level = "warning" if lines else "notice"
print(f"::{level} title=Allocation stacks::" + "%0A".join([summary] + lines))
