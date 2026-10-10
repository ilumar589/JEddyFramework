"""Turns a jeddy-cook report.json into one GitHub notice: texture quality and shader checks."""
import json
import os
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
lines = []
for item in report["items"]:
    s = item.get("stats", {})
    if "psnr" in s:
        lines.append(f"{item['source']}: {s['format']} {s['width']}x{s['height']}, PSNR {s['psnr']} dB, {s['encodeMs']} ms")
    for t in s.get("textures", []):
        lines.append(f"{t['texture']}: {t['format']} {t['width']}x{t['height']}, PSNR {t['psnr']} dB")
    if "validated" in s:
        lines.append(f"{item['source']}: {s['validated']} of {s['permutations']} permutations compiled")
    if "bakeMs" in s:
        lines.append(f"{item['source']}: environment {s['faceSize']}px x {s['levels']} levels in {s['bakeMs']} ms")
    if item.get("warnings"):
        lines.append(f"{item['source']}: warnings {item['warnings']}")
counts = report["counts"]
title = f"Sample cook ({os.environ.get('RUNNER_OS', 'local')}): {counts['cooked']} cooked, {counts['cached']} cached, {counts['failed']} failed"
print(f"::notice title={title}::" + "%0A".join(l.replace("%", "%25") for l in lines))
