#!/usr/bin/env python3
"""Turns failed JUnit results into GitHub annotations.

Gradle keeps test output in build/test-results/**/TEST-*.xml rather than the console, so a crash
logged by the code under test is invisible in the job log. This prints one annotation per failed
test with the failure, the start of its stack trace and the tail of the suite's stdout/stderr.

Usage: annotate-tests.py [root]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 8
TRACE_LINES = 25
OUTPUT_LINES = 40


def encode(text):
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def tail(text, lines):
    parts = (text or "").strip("\n").splitlines()
    return "\n".join(parts[-lines:])


def head(text, lines):
    parts = (text or "").strip("\n").splitlines()
    return "\n".join(parts[:lines])


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    pattern = os.path.join(root, "**", "build", "test-results", "**", "TEST-*.xml")
    emitted = 0
    for path in sorted(glob.glob(pattern, recursive=True)):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError as e:
            print(f"::warning title=Unreadable test result::{path}: {e}")
            continue
        out = suite.findtext("system-out") or ""
        err = suite.findtext("system-err") or ""
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            name = f"{case.get('classname')}.{case.get('name')}"
            body = [
                problem.get("message") or "",
                head(problem.text, TRACE_LINES),
            ]
            if err.strip():
                body += ["--- stderr (tail) ---", tail(err, OUTPUT_LINES)]
            if out.strip():
                body += ["--- stdout (tail) ---", tail(out, OUTPUT_LINES)]
            message = encode("\n".join(body))[:60000]
            print(f"::error title=Test failed: {name}::{message}")
            emitted += 1
            if emitted >= MAX_ANNOTATIONS:
                return


if __name__ == "__main__":
    main()
