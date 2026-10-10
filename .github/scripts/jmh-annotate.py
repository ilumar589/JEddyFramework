#!/usr/bin/env python3
"""Posts JMH results (build/reports/jmh/results.json) as one GitHub notice annotation, so
benchmark numbers from CI runners are readable from the PR view and the checks API."""
import json
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "bench/build/reports/jmh/results.json"
try:
    results = json.load(open(path))
except (OSError, ValueError) as e:
    print(f"::warning title=JMH::no results ({e})")
    sys.exit(0)
lines = []
for r in results:
    name = r["benchmark"].rsplit(".", 2)[-2:]
    m = r["primaryMetric"]
    err = m.get("scoreError")
    err_txt = f" ± {err:.3g}" if isinstance(err, (int, float)) and err == err else ""
    params = r.get("params") or {}
    ptxt = "(" + ", ".join(f"{k}={v}" for k, v in params.items()) + ")" if params else ""
    extra = ""
    if name[0] == "ZstdBench" and m["scoreUnit"] == "ms/op" and m["score"] > 0:
        extra = f" = {8 * 1048576 / 1e6 / (m['score'] / 1000):.0f} MB/s"  # 8 MiB per op
    lines.append(f"{name[0]}.{name[1]}{ptxt}: {m['score']:.4g}{err_txt} {m['scoreUnit']}{extra}")
msg = "%0A".join(lines)
print(f"::notice title=JMH results ({len(lines)} benchmarks)::{msg}")
