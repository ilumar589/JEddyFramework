#!/usr/bin/env bash
# Runs `zstd -b` at levels 1, 3 and 19 on the corpus ZstdBench wrote and posts the speeds as a
# notice annotation, next to the JMH numbers of the FFM facade (TASKS M4-01).
#
# Usage: zstd-cli-bench.sh <corpus file>
set -u
corpus="${1:-zstd-corpus.bin}"
if [ ! -f "$corpus" ]; then
  echo "::warning title=zstd CLI::no corpus at $corpus (did ZstdBench run?)"
  exit 0
fi
if ! command -v zstd > /dev/null; then
  sudo apt-get install -y -q zstd > /dev/null 2>&1 || true
fi
if ! command -v zstd > /dev/null; then
  echo "::warning title=zstd CLI::zstd is not installed"
  exit 0
fi
# zstd -b redraws its progress line with CR; keep the final line of each level.
results=$( { zstd -b1 -i2 "$corpus"; zstd -b3 -i2 "$corpus"; zstd -b19 -i1 "$corpus"; } 2>&1 \
  | tr '\r' '\n' | grep -E '^ *[0-9]+#' | awk -F'#' '{ last[$1 + 0] = $0 } END { for (k in last) print last[k] }' | sort -n)
version=$(zstd --version 2>&1 | head -n 1)
msg=$(printf '%s\n%s\n' "$version (MB = 10^6 bytes; compress, decompress)" "$results" | sed -e 's/%/%25/g' | awk 'BEGIN { ORS = "%0A" } { print }')
echo "::notice title=zstd CLI on the ZstdBench corpus::$msg"
