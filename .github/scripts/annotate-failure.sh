#!/usr/bin/env bash
# Turns the useful parts of a failed Gradle log into GitHub annotations, so a failure can be
# read from the PR view and the checks API without downloading the raw job log.
#
# Usage: annotate-failure.sh <gradle log file>
set -u
log="${1:-build.log}"

if [ ! -f "$log" ]; then
  echo "::error title=CI::No log file '$log' (the build failed before Gradle produced output)"
  exit 0
fi

# Annotation messages are one line: encode %, CR and LF as the workflow-command spec requires.
encode() { sed -e 's/%/%25/g' -e 's/\r//g' | awk 'BEGIN { ORS = "%0A" } { print }'; }

what=$(sed -n '/What went wrong/,/^\* Try:/p' "$log" | head -n 80 | encode)
if [ -n "$what" ]; then
  echo "::error title=Gradle failure::${what}"
fi

# javac (error:/warning:), Kotlin (e:/w: file:...), JUnit and Gradle task failures.
problems=$(grep -E '(error:|warning:|^e: |^w: |FAILED|Exception|Caused by:|expected:)' "$log" | grep -v '^\s*at ' | head -n 60 | encode)
if [ -n "$problems" ]; then
  echo "::error title=Compiler and test problems::${problems}"
fi

# Test stdout/stderr only reaches the JUnit XML files, not the console.
dir=$(dirname "$0")
if command -v python3 > /dev/null 2>&1 && python3 --version > /dev/null 2>&1; then
  python3 "$dir/annotate-tests.py" .
elif command -v python > /dev/null 2>&1; then
  python "$dir/annotate-tests.py" .
fi

tail_lines=$(tail -n 40 "$log" | encode)
echo "::error title=Log tail::${tail_lines}"
