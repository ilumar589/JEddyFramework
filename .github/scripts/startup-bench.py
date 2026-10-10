#!/usr/bin/env python3
"""Cold start to first frame (DESIGN §14, TASKS M11-02): runs a command several times, reads the
"startup: first frame N ms after JVM start" line it prints, and reports the median as a GitHub
annotation (a warning above the budget).

Usage: startup-bench.py LABEL RUNS BUDGET_MS -- COMMAND [ARGS...]"""
import re
import statistics
import subprocess
import sys

label, runs, budget = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
command = sys.argv[sys.argv.index("--") + 1:]
times = []
for i in range(runs):
    run = subprocess.run(command, capture_output=True, text=True, timeout=300)
    match = re.search(r"startup: first frame (\d+) ms", run.stdout + run.stderr)
    if run.returncode != 0 or not match:
        print((run.stdout + run.stderr)[-3000:])
        print(f"::error title=Startup ({label})::run {i + 1} failed (exit {run.returncode})")
        sys.exit(1)
    times.append(int(match.group(1)))
median = statistics.median(times)
summary = f"median {median:.0f} ms, min {min(times)}, max {max(times)} over {runs} runs: {times}"
print(f"{label}: {summary}")
level = "warning" if median > budget else "notice"
print(f"::{level} title=Startup ({label})::first frame {summary} (budget {budget:.0f} ms)")
