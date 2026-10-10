#!/usr/bin/env python3
"""Classes the JVM loaded during the second half of a perf capture (DESIGN §13.3): late class
loading is one-time linkage in steady state (spun method-handle forms, lazily initialized
holders), which usually allocates. Reads perf.json for the window and a class-load log written
with -Xlog:class+load=info:file=<log>:uptime, and posts a GitHub annotation.

Usage: late-classes.py PERF_JSON CLASSLOAD_LOG"""
import json
import re
import sys

perf_path, log_path = sys.argv[1], sys.argv[2]
try:
    perf = json.load(open(perf_path))
    lines = open(log_path, encoding="utf-8", errors="replace").read().splitlines()
except (OSError, ValueError) as e:
    print(f"::notice title=Late class loading::no data ({e})")
    sys.exit(0)
start, end = perf.get("uptimeStartMs"), perf.get("uptimeEndMs")
if start is None or end is None:
    print("::notice title=Late class loading::perf.json has no uptime window")
    sys.exit(0)
mid = (start + end) / 2
late = []
for line in lines:
    m = re.match(r"^\[(\d+(?:\.\d+)?)s\](?:\[[^\]]*\])*\s*(\S+)(.*)$", line)
    if not m:
        continue
    t = float(m.group(1)) * 1000
    if mid <= t <= end:
        late.append(f"{t / 1000:.1f} s {m.group(2)}{m.group(3)[:60]}")
summary = f"{len(late)} classes loaded in the second half ({mid / 1000:.0f}-{end / 1000:.0f} s uptime)"
print(summary)
for entry in late[:200]:
    print("  " + entry)
level = "warning" if late else "notice"
print(f"::{level} title=Late class loading::" + "%0A".join([summary] + late[:40]))
