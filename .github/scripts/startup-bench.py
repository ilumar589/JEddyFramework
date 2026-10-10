#!/usr/bin/env python3
"""Cold start to first frame (DESIGN §14, TASKS M11-02): runs a command several times, reads the
"startup: first frame N ms after JVM start" line it prints, and reports the median as a GitHub
annotation (a warning above the budget).

Usage: startup-bench.py LABEL RUNS BUDGET_MS -- COMMAND [ARGS...]"""
import os
import re
import statistics
import subprocess
import sys

label, runs, budget = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
command = sys.argv[sys.argv.index("--") + 1:]
# cmd.exe misreads a batch file path written with forward slashes (as Git Bash passes it).
if os.path.isfile(command[0]):
    command[0] = os.path.abspath(command[0])
times = []
for i in range(runs):
    run = subprocess.run(command, capture_output=True, text=True, timeout=300)
    output = run.stdout + run.stderr
    match = re.search(r"startup: first frame (\d+) ms", output)
    if run.returncode != 0 or not match:
        print(output[-3000:])
        tail = output[-1500:].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Startup ({label})::run {i + 1} failed (exit {run.returncode})%0A{tail}")
        sys.exit(1)
    times.append(int(match.group(1)))
median = statistics.median(times)
summary = f"median {median:.0f} ms, min {min(times)}, max {max(times)} over {runs} runs: {times}"
print(f"{label}: {summary}")
level = "warning" if median > budget else "notice"
print(f"::{level} title=Startup ({label})::first frame {summary} (budget {budget:.0f} ms)")
