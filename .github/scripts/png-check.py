#!/usr/bin/env python3
"""Fails unless a PNG (8-bit RGB or RGBA, as the demo writes) shows a real frame: enough distinct
colours and contrast that it is not a cleared or black screen. Usage: png-check.py FILE [MIN_COLOURS]"""
import struct
import sys
import zlib

path = sys.argv[1]
min_colours = int(sys.argv[2]) if len(sys.argv) > 2 else 500
data = open(path, "rb").read()
assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
pos, idat, width, height, channels = 8, b"", 0, 0, 3
while pos < len(data):
    n, kind = struct.unpack(">I4s", data[pos:pos + 8])
    body = data[pos + 8:pos + 8 + n]
    if kind == b"IHDR":
        width, height, depth, colour = struct.unpack(">IIBB", body[:10])
        assert depth == 8 and colour in (2, 6), "expects 8-bit RGB(A)"
        channels = 3 if colour == 2 else 4
    elif kind == b"IDAT":
        idat += body
    pos += 12 + n
raw = zlib.decompress(idat)
stride = width * channels
colours, lo, hi = set(), 255, 0
prev = bytearray(stride)
for y in range(height):
    f = raw[y * (stride + 1)]
    row = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
    for i in range(stride):  # undo the PNG filters
        a = row[i - channels] if i >= channels else 0
        b = prev[i]
        c = prev[i - channels] if i >= channels else 0
        if f == 1:
            row[i] = (row[i] + a) & 255
        elif f == 2:
            row[i] = (row[i] + b) & 255
        elif f == 3:
            row[i] = (row[i] + (a + b) // 2) & 255
        elif f == 4:
            p = a + b - c
            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            row[i] = (row[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
    prev = row
    if y % 4 == 0:
        for x in range(0, width, 4):
            px = bytes(row[x * channels:x * channels + 3])
            colours.add(px)
            lum = (px[0] * 3 + px[1] * 6 + px[2]) // 10
            lo, hi = min(lo, lum), max(hi, lum)
print(f"{path}: {width}x{height}, {len(colours)} colours sampled, luminance {lo}..{hi}")
if len(colours) < min_colours or hi - lo < 40:
    print(f"::error title=Screenshot::{path} looks blank ({len(colours)} colours, luminance {lo}..{hi})")
    sys.exit(1)
