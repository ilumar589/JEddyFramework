#!/usr/bin/env python3
"""Generates the iso-demo source assets procedurally (no third-party content, no licenses).

Run from the repository root:  python3 samples/iso-demo/tools/generate_assets.py
The output is deterministic, so regenerating changes nothing unless this script changes.
"""
import base64
import json
import math
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")


def write(path, data):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    mode = "wb" if isinstance(data, (bytes, bytearray)) else "w"
    with open(full, mode, newline="\n" if mode == "w" else None) as f:
        f.write(data)


def png(w, h, pixel):
    """RGBA8 PNG; pixel(x, y) returns (r, g, b, a) in 0..255."""
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(bytes(max(0, min(255, int(round(c)))) for c in pixel(x, y)))

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def hash01(x, y, seed):
    n = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def planks(x, y):
    board = (y // 16) % 2
    grain = 0.5 + 0.5 * math.sin(x * 0.35 + board * 2 + math.sin(y * 0.2) * 1.5)
    seam = 0.55 if y % 16 == 0 else 1.0
    n = hash01(x, y, 1) * 0.08
    return (150 * seam * (0.8 + 0.2 * grain + n), 100 * seam * (0.8 + 0.2 * grain + n), 60 * seam * (0.8 + n), 255)


def planks_normal(x, y):
    nx = 0.25 * math.cos(x * 0.35)
    ny = -0.6 if y % 16 == 0 else (0.6 if y % 16 == 1 else 0.0)
    nz = math.sqrt(max(0.0, 1 - nx * nx - ny * ny))
    l = math.sqrt(nx * nx + ny * ny + nz * nz)
    return ((nx / l * 0.5 + 0.5) * 255, (ny / l * 0.5 + 0.5) * 255, (nz / l * 0.5 + 0.5) * 255, 255)


def planks_orm(x, y):
    ao = 0.6 if y % 16 == 0 else 1.0
    return (ao * 255, (0.6 + 0.2 * hash01(x, y, 2)) * 255, 0, 255)


def stone(x, y):
    cell = hash01(x // 8, y // 8, 3)
    n = hash01(x, y, 4)
    v = 110 + 50 * cell + 20 * n
    return (v, v * 0.97, v * 0.92, 255)


def stone_normal(x, y):
    fx, fy = (x % 8) - 3.5, (y % 8) - 3.5
    nx, ny = (0.3 if abs(fx) > 3 else 0) * (1 if fx > 0 else -1), (0.3 if abs(fy) > 3 else 0) * (1 if fy > 0 else -1)
    nz = math.sqrt(1 - nx * nx - ny * ny)
    return ((nx * 0.5 + 0.5) * 255, (ny * 0.5 + 0.5) * 255, (nz * 0.5 + 0.5) * 255, 255)


def grass(x, y):
    n = hash01(x, y, 5)
    return (60 + 30 * n, 110 + 40 * n, 50 + 20 * n, 255)


def hdr(w, h, radiance):
    out = bytearray(b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y %d +X %d\n" % (h, w))
    for y in range(h):
        for x in range(w):
            r, g, b = radiance(x, y)
            m = max(r, g, b)
            if m < 1e-32:
                out.extend(b"\0\0\0\0")
                continue
            mant, e = math.frexp(m)
            scale = mant * 256.0 / m
            out.extend(bytes((min(255, int(r * scale)), min(255, int(g * scale)), min(255, int(b * scale)), e + 128)))
    return bytes(out)


def sky(x, y, w=128, h=64):
    theta = (y + 0.5) / h * math.pi  # 0 = up
    phi = (x + 0.5) / w * 2 * math.pi
    up = math.cos(theta)
    if up >= 0:
        base = (0.35 + 0.5 * (1 - up), 0.55 + 0.35 * (1 - up), 1.0)
    else:
        base = (0.25, 0.22, 0.18)
    # A small sun.
    sun_dir = (math.sin(0.9) * math.cos(1.2), math.cos(0.9), math.sin(0.9) * math.sin(1.2))
    d = (math.sin(theta) * math.cos(phi), math.cos(theta), math.sin(theta) * math.sin(phi))
    dot = sum(a * b for a, b in zip(d, sun_dir))
    sun = 40.0 if dot > 0.995 else 0.0
    return (base[0] + sun, base[1] + sun * 0.95, base[2] + sun * 0.85)


def cube_and_ground():
    """Interleaved-free streams for a unit cube (24 vertices) and a 2x2 ground plane."""
    pos, nrm, uv, idx = [], [], [], []
    faces = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]
    for n in faces:
        u = (0, n[0], 0) if n[0] else ((0, 0, n[1]) if n[1] else (n[2], 0, 0))
        w = (n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
        base = len(pos) // 3
        for cu, cw in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            pos += [0.5 * (n[k] + cu * u[k] + cw * w[k]) for k in range(3)]
            nrm += list(n)
            uv += [(cu + 1) / 2, (1 - cw) / 2]
        idx += [base, base + 1, base + 2, base, base + 2, base + 3]
    gpos = [-1, 0, -1, 1, 0, -1, 1, 0, 1, -1, 0, 1]
    gnrm = [0, 1, 0] * 4
    guv = [0, 0, 8, 0, 8, 8, 0, 8]
    gidx = [0, 3, 2, 0, 2, 1]
    return (pos, nrm, uv, idx), (gpos, gnrm, guv, gidx)


def gltf():
    (cp, cn, cu, ci), (gp, gn, gu, gi) = cube_and_ground()
    blob = bytearray()
    views, accessors = [], []

    def add(values, fmt, ctype, kind, count, minmax=None, target=None):
        while len(blob) % 4:
            blob.append(0)
        offset = len(blob)
        blob.extend(struct.pack("<%d%s" % (len(values), fmt), *values))
        view = {"buffer": 0, "byteOffset": offset, "byteLength": len(blob) - offset}
        if target:
            view["target"] = target
        views.append(view)
        acc = {"bufferView": len(views) - 1, "componentType": ctype, "count": count, "type": kind}
        if minmax:
            acc["min"], acc["max"] = minmax
        accessors.append(acc)
        return len(accessors) - 1

    def bounds(p):
        xs, ys, zs = p[0::3], p[1::3], p[2::3]
        return [min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)]

    c = [add(cp, "f", 5126, "VEC3", 24, bounds(cp), 34962), add(cn, "f", 5126, "VEC3", 24, None, 34962),
         add(cu, "f", 5126, "VEC2", 24, None, 34962), add(ci, "H", 5123, "SCALAR", 36, None, 34963)]
    g = [add(gp, "f", 5126, "VEC3", 4, bounds(gp), 34962), add(gn, "f", 5126, "VEC3", 4, None, 34962),
         add(gu, "f", 5126, "VEC2", 4, None, 34962), add(gi, "H", 5123, "SCALAR", 6, None, 34963)]
    times = add([0.0, 1.0, 2.0], "f", 5126, "SCALAR", 3, ([0.0], [2.0]))
    bob = add([0, 0.5, 0, 0, 0.7, 0, 0, 0.5, 0], "f", 5126, "VEC3", 3)
    doc = {
        "asset": {"version": "2.0", "generator": "jeddy iso-demo generate_assets.py"},
        "scene": 0,
        "scenes": [{"name": "yard", "nodes": [0]}],
        "nodes": [
            {"name": "yard", "children": [1, 2, 3]},
            {"name": "crate", "mesh": 0, "translation": [0, 0.5, 0]},
            {"name": "ground", "mesh": 1, "scale": [10, 1, 10]},
            {"name": "sun", "rotation": [-0.3826834, 0.0, 0.0, 0.9238795],
             "extensions": {"KHR_lights_punctual": {"light": 0}}},
        ],
        "meshes": [
            {"name": "crate", "primitives": [{"attributes": {"POSITION": c[0], "NORMAL": c[1], "TEXCOORD_0": c[2]},
                                              "indices": c[3], "material": 0}]},
            {"name": "ground", "primitives": [{"attributes": {"POSITION": g[0], "NORMAL": g[1], "TEXCOORD_0": g[2]},
                                               "indices": g[3], "material": 1}]},
        ],
        "materials": [
            {"name": "crate", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0},
                                                       "metallicRoughnessTexture": {"index": 2}},
             "normalTexture": {"index": 1}, "occlusionTexture": {"index": 2}},
            {"name": "grass", "pbrMetallicRoughness": {"baseColorTexture": {"index": 3}, "metallicFactor": 0.0,
                                                       "roughnessFactor": 0.9}},
        ],
        "textures": [{"source": i, "sampler": 0} for i in range(4)],
        "samplers": [{"wrapS": 10497, "wrapT": 10497}],
        "images": [{"uri": "textures/crate_basecolor.png"}, {"uri": "textures/crate_normal.png"},
                   {"uri": "textures/crate_orm.png"}, {"uri": "textures/grass.png"}],
        "animations": [{"name": "bob", "samplers": [{"input": times, "output": bob}],
                        "channels": [{"sampler": 0, "target": {"node": 1, "path": "translation"}}]}],
        "extensionsUsed": ["KHR_lights_punctual"],
        "extensions": {"KHR_lights_punctual": {"lights": [{"name": "sun", "type": "directional", "intensity": 3.0,
                                                            "color": [1.0, 0.95, 0.85]}]}},
        "buffers": [{"uri": "yard.bin", "byteLength": len(blob)}],
        "bufferViews": views,
        "accessors": accessors,
    }
    return json.dumps(doc, indent=1) + "\n", bytes(blob)


def main():
    text, blob = gltf()
    write("models/yard.gltf", text)
    write("models/yard.bin", blob)
    write("models/textures/crate_basecolor.png", png(64, 64, planks))
    write("models/textures/crate_normal.png", png(64, 64, planks_normal))
    write("models/textures/crate_orm.png", png(64, 64, planks_orm))
    write("models/textures/grass.png", png(64, 64, grass))
    write("textures/stone.png", png(64, 64, stone))
    write("textures/stone_n.png", png(64, 64, stone_normal))
    write("materials/stone.jmat.json", json.dumps({
        "baseColor": [1, 1, 1, 1], "roughness": 0.8, "metallic": 0,
        "textures": {"baseColor": "../textures/stone.png", "normal": "../textures/stone_n.png"}}, indent=1) + "\n")
    write("env/sky.hdr", hdr(128, 64, sky))
    write("env/sky.hdr.meta", json.dumps({"size": 64, "samples": 64}) + "\n")
    write("shaders/common/fog.glsl", "#pragma once\n\nvec3 applyFog(vec3 color, float depth) {\n"
          "#ifdef FOG\n    return mix(color, vec3(0.6, 0.7, 0.8), clamp(depth * 0.02, 0.0, 1.0));\n#else\n"
          "    return color;\n#endif\n}\n")
    write("shaders/ground.frag", "#version 460 core\n\n#include \"common/fog.glsl\"\n\n"
          "layout(location = 0) in vec2 uv;\nlayout(location = 1) in float depth;\n"
          "layout(location = 0) out vec4 color;\n\nlayout(binding = 0) uniform sampler2D albedo;\n\n"
          "void main() {\n    color = vec4(applyFog(texture(albedo, uv).rgb, depth), 1.0);\n}\n")
    write("shaders/ground.frag.meta", json.dumps({"permutations": [[], ["FOG"]]}) + "\n")
    write("config/game.json", json.dumps({"name": "Jeddy iso demo", "startArea": "yard"}, indent=1) + "\n")


if __name__ == "__main__":
    main()
