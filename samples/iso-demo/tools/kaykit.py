#!/usr/bin/env python3
"""Builds the iso-demo's CC0 content from KayKit packs by Kay Lousberg (TASKS M10-01).

Fetches three KayKit asset packs (CC0 1.0, https://kaylousberg.com) at pinned commits, then:

* writes a prefab library of the Dungeon Remastered pieces the crypt uses, one root node per
  piece (assets/levels/dungeon.glb, DESIGN §19, D87), and assembles the "crypt" level as
  placements of those prefabs laid out on a 4 m grid (assets/levels/crypt.jlevel, the binary
  level format of docs/formats/jlevel.md), with the collision rules by layer (floor, wall, prop,
  decor), the navmesh and occlusion settings, the torch lights and the party and skeleton spawns;
* copies the party (Adventurers) and the enemies (Skeletons) without their 76 animations each,
  with textures embedded (assets/characters/*.glb);
* writes one animation library per rig (assets/characters/anims/heroes.glb and skeletons.glb,
  TASKS M12-06): the rig's nodes without meshes and the subset of clips the demo plays, constant
  channels reduced to a single key (lossless);

Run from the repository root:  python3 samples/iso-demo/tools/kaykit.py [--src DIR]
Sources are cached in samples/iso-demo/build/kaykit-src (or DIR). The output is deterministic.
"""
import argparse
import json
import math
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "assets")

PACKS = {
    "dungeon": ("KayKit-Dungeon-Remastered-1.0", "b0ca9bd96a8072ab36a3a5464f00ed1e06a16d07",
                "addons/kaykit_dungeon_remastered/Assets/gltf"),
    "heroes": ("KayKit-Character-Pack-Adventures-1.0", "672074b73ba276876a19e8816ecdc5241817ab47",
               "addons/kaykit_character_pack_adventures/Characters/gltf"),
    "skeletons": ("KayKit-Character-Pack-Skeletons-1.0", "15b62b9bad122f72926c10fb14d622c73819fa54",
                  "addons/kaykit_character_pack_skeletons/Characters/gltf"),
}

# The crypt, 4 m per cell. '#' rock, '.' floor, 'P' floor with a pillar, 'B' barrels, 'C' crates,
# 'T' table, 'K' chest, 'R' rubble, 'H' party start, 'S' skeleton spawn, 'D' doorway (floor with a
# door frame between the two rooms it joins), 'G' grate (difficult terrain).
LAYOUT = [
    "######################",
    "#.....#######........#",
    "#.HH..#######..S..S..#",
    "#.HH..D.....D........#",
    "#.....#######..P..P..#",
    "#..K..#######........#",
    "###.##########.....###",
    "###.##########D.######",
    "#.....##......S.....##",
    "#.B...##.P..P....P..##",
    "#..GG.D.............##",
    "#..GG.##..T....SS...##",
    "#...C.##.P..P....P..##",
    "#.....##.......S..R.##",
    "######################",
]
CELL = 4.0


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def fetch(src):
    """Shallow, sparse checkouts of the pinned commits."""
    for name, (repo, commit, path) in PACKS.items():
        d = os.path.join(src, repo)
        marker = os.path.join(d, ".jeddy-commit")
        if os.path.exists(marker) and open(marker).read().strip() == commit:
            continue
        os.makedirs(d, exist_ok=True)
        if not os.path.isdir(os.path.join(d, ".git")):
            run("git", "init", "-q", cwd=d)
            run("git", "remote", "add", "origin", f"https://github.com/KayKit-Game-Assets/{repo}", cwd=d)
        run("git", "sparse-checkout", "set", "--no-cone", "/" + path, "/LICENSE.txt", cwd=d)
        run("git", "fetch", "-q", "--depth", "1", "origin", commit, cwd=d)
        run("git", "checkout", "-q", "FETCH_HEAD", cwd=d)
        with open(marker, "w") as f:
            f.write(commit + "\n")


def pack_dir(src, name):
    repo, _, path = PACKS[name]
    return os.path.join(src, repo, path)


# --- glTF helpers -------------------------------------------------------------------------------

def read_glb(path):
    b = open(path, "rb").read()
    magic, version, length = struct.unpack_from("<III", b, 0)
    if magic != 0x46546C67:
        raise ValueError(path + " is not a glb")
    jl, jt = struct.unpack_from("<II", b, 12)
    j = json.loads(b[20:20 + jl].decode("utf-8"))
    o = 20 + jl
    bin_ = b""
    if o < len(b):
        bl, bt = struct.unpack_from("<II", b, o)
        bin_ = b[o + 8:o + 8 + bl]
    # Inline external images (uri next to the file).
    for img in j.get("images", []):
        if "uri" in img and not img["uri"].startswith("data:"):
            img["_bytes"] = open(os.path.join(os.path.dirname(path), img.pop("uri")), "rb").read()
            img.setdefault("mimeType", "image/png")
    return j, bin_


class Builder:
    """Accumulates a new glTF: buffer, views, accessors, meshes, materials, images, nodes."""

    def __init__(self):
        self.bin = bytearray()
        self.j = {"asset": {"version": "2.0", "generator": "jeddy kaykit.py"},
                  "scene": 0, "scenes": [{"nodes": []}], "nodes": [], "meshes": [], "materials": [],
                  "textures": [], "images": [], "samplers": [], "accessors": [], "bufferViews": []}
        self.images = {}

    def view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        v = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if target:
            v["target"] = target
        self.bin += data
        self.j["bufferViews"].append(v)
        return len(self.j["bufferViews"]) - 1

    def copy_accessor(self, src_j, src_bin, a, cache):
        if a in cache:
            return cache[a]
        acc = dict(src_j["accessors"][a])
        if "sparse" in acc:
            raise ValueError("sparse accessors are not copied")
        sv = src_j["bufferViews"][acc["bufferView"]]
        start = sv.get("byteOffset", 0)
        data = src_bin[start:start + sv["byteLength"]]
        nv = self.view(data, sv.get("target"))
        if "byteStride" in sv:
            self.j["bufferViews"][nv]["byteStride"] = sv["byteStride"]
        acc["bufferView"] = nv
        self.j["accessors"].append(acc)
        cache[a] = len(self.j["accessors"]) - 1
        return cache[a]

    def image(self, src_j, src_bin, i):
        img = src_j["images"][i]
        name = img.get("name", "image%d" % i)
        if name in self.images:
            return self.images[name]
        if "_bytes" in img:
            data = img["_bytes"]
        else:
            sv = src_j["bufferViews"][img["bufferView"]]
            start = sv.get("byteOffset", 0)
            data = src_bin[start:start + sv["byteLength"]]
        v = self.view(data)
        self.j["images"].append({"bufferView": v, "mimeType": img.get("mimeType", "image/png"), "name": name})
        self.images[name] = len(self.j["images"]) - 1
        return self.images[name]

    def texture(self, src_j, src_bin, t):
        tex = src_j["textures"][t]
        img = self.image(src_j, src_bin, tex["source"])
        for k, existing in enumerate(self.j["textures"]):
            if existing["source"] == img:
                return k
        out = {"source": img}
        if "sampler" in tex:
            self.j["samplers"].append(dict(src_j["samplers"][tex["sampler"]]))
            out["sampler"] = len(self.j["samplers"]) - 1
        self.j["textures"].append(out)
        return len(self.j["textures"]) - 1

    def material(self, src_j, src_bin, m, cache):
        if m in cache:
            return cache[m]
        mat = json.loads(json.dumps(src_j["materials"][m]))
        for owner, key in ((mat.get("pbrMetallicRoughness", {}), "baseColorTexture"),
                           (mat.get("pbrMetallicRoughness", {}), "metallicRoughnessTexture"),
                           (mat, "normalTexture"), (mat, "occlusionTexture"), (mat, "emissiveTexture")):
            if key in owner:
                owner[key]["index"] = self.texture(src_j, src_bin, owner[key]["index"])
        # Pieces of one pack share their atlas material: deduplicate by content.
        text = json.dumps(mat, sort_keys=True)
        for k, existing in enumerate(self.j["materials"]):
            if json.dumps(existing, sort_keys=True) == text:
                cache[m] = k
                return k
        self.j["materials"].append(mat)
        cache[m] = len(self.j["materials"]) - 1
        return cache[m]

    def mesh(self, src_j, src_bin, m, acc_cache, mat_cache):
        mesh = src_j["meshes"][m]
        prims = []
        for p in mesh["primitives"]:
            q = {"attributes": {k: self.copy_accessor(src_j, src_bin, v, acc_cache)
                                for k, v in p["attributes"].items()}}
            if "indices" in p:
                q["indices"] = self.copy_accessor(src_j, src_bin, p["indices"], acc_cache)
            if "material" in p:
                q["material"] = self.material(src_j, src_bin, p["material"], mat_cache)
            if "mode" in p:
                q["mode"] = p["mode"]
            prims.append(q)
        self.j["meshes"].append({"name": mesh.get("name", "mesh%d" % m), "primitives": prims})
        return len(self.j["meshes"]) - 1

    def node(self, n, root=False):
        self.j["nodes"].append(n)
        k = len(self.j["nodes"]) - 1
        if root:
            self.j["scenes"][0]["nodes"].append(k)
        return k

    def write(self, path):
        for key in ("samplers", "textures", "images", "materials"):
            if not self.j[key]:
                del self.j[key]
        while len(self.bin) % 4:
            self.bin.append(0)
        self.j["buffers"] = [{"byteLength": len(self.bin)}]
        js = json.dumps(self.j, separators=(",", ":"), sort_keys=True).encode("utf-8")
        while len(js) % 4:
            js += b" "
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        out = struct.pack("<III", 0x46546C67, 2, total)
        out += struct.pack("<II", len(js), 0x4E4F534A) + js
        out += struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(out)
        return len(out)


def yaw(deg):
    a = math.radians(deg) / 2
    return [0.0, round(math.sin(a), 7), 0.0, round(math.cos(a), 7)]


# --- level ----------------------------------------------------------------------------------

class Pieces:
    """Dungeon pieces loaded once into a prefab library (one root node per piece) and placed."""

    def __init__(self, builder, directory):
        self.b = builder
        self.dir = directory
        self.loaded = {}
        self.placements = []

    def get(self, name):
        """Adds piece `name` to the library once (a root node named after it) and returns it."""
        if name in self.loaded:
            return self.loaded[name]
        path = os.path.join(self.dir, name + ".gltf.glb")
        if not os.path.exists(path):
            path = os.path.join(self.dir, name + ".glb")
        j, bin_ = read_glb(path)
        acc, mats = {}, {}
        count = [0]

        def visit(n):
            src = j["nodes"][n]
            out = {k: src[k] for k in ("translation", "rotation", "scale") if k in src}
            out["name"] = f"{name}_{count[0]}"
            count[0] += 1
            if "mesh" in src:
                out["mesh"] = self.b.mesh(j, bin_, src["mesh"], acc, mats)
            k = self.b.node(out)
            children = [visit(c) for c in src.get("children", [])]
            if children:
                self.b.j["nodes"][k]["children"] = children
            return k

        kids = [visit(n) for n in j["scenes"][j.get("scene", 0)]["nodes"]]
        root = self.b.node({"name": name, "children": kids}, root=True)
        self.loaded[name] = root
        return root

    def place(self, name, layer, x, z, deg=0.0, y=0.0):
        self.get(name)
        self.placements.append((name, layer, x, y, z, deg))


# Triangle flags of docs/formats/jcol.md (physics.bvh.TriFlags).
WALKABLE, BLOCKS_LOS, BLOCKS_PROJECTILE, CAMERA_FADE, BLOCKS_MOVEMENT = 1, 2, 4, 8, 16
# Placement flags of docs/formats/jlevel.md.
P_STATIC, P_CASTS_SHADOW, P_FADE_OCCLUDER = 1, 2, 4
L_FLICKER = 2


class JLevel:
    """Writes a .jlevel (docs/formats/jlevel.md) from plain lists; the Java LevelData reads it."""

    def __init__(self):
        self.strings = bytearray()
        self.prefabs = []       # (scene, node)
        self.placements = []    # (prefab, name, layer, flags, x, y, z, qx, qy, qz, qw, sx, sy, sz)
        self.lights = []        # (type, name, flags, 13 floats)
        self.spawns = []        # (name, kind, x, y, z, yaw)
        self.triggers = []
        self.areas = []         # (area, yMin, yMax, [x, z, ...])
        self.links = []
        self.rules = []         # (layer glob, flags, surface, area)
        self.sizes = []         # (name, radius, height, climb)
        self.settings = {"navCellSize": 0.15, "navCellHeight": 0.1, "navTileSize": 32.0,
                         "navMaxSlope": 45.0, "occlusionCellSize": 0.5, "occlusionYMin": 0.3,
                         "occlusionYMax": 2.0, "occlusionEnabled": 1, "sun": [-0.4, -0.8, -0.45],
                         "sunColor": [3.0, 2.9, 2.7], "exposure": 1.0, "envSpecular": 1.0,
                         "envDiffuse": 1.0, "environment": "", "gridStep": 0.5}

    def prefab(self, scene, node):
        key = (scene, node)
        if key not in self.prefabs:
            self.prefabs.append(key)
        return self.prefabs.index(key)

    def string(self, s):
        if not s:
            return (0, 0)
        data = s.encode("utf-8")
        off = len(self.strings)
        self.strings += data
        return (off, len(data))

    @staticmethod
    def align(n):
        return (n + 15) & ~15

    def write(self, path):
        sizes = (16, 96, 80, 48, 64, 32, 48, 24, 32)
        counts = (len(self.prefabs), len(self.placements), len(self.lights), len(self.spawns),
                  len(self.triggers), len(self.areas), len(self.links), len(self.rules), len(self.sizes))
        offsets = []
        at = 160
        for size, count in zip(sizes, counts):
            offsets.append(at)
            at = self.align(at + size * count)
        settings_at = at
        polygons_at = self.align(at + 128)
        polygon_floats = sum(len(a[3]) for a in self.areas)
        strings_at = self.align(polygons_at + 4 * polygon_floats)
        out = bytearray(strings_at)
        ids = iter(range(1, 1 << 30))
        next_id = 1

        def put(fmt, off, *values):
            struct.pack_into("<" + fmt, out, off, *values)

        def put_string(off, s):
            put("II", off, *self.string(s))

        for i, (scene, node) in enumerate(self.prefabs):
            at = offsets[0] + 16 * i
            put_string(at, scene)
            put_string(at + 8, node)
        for i, p in enumerate(self.placements):
            at = offsets[1] + 96 * i
            next_id = next(ids)
            put("II", at, next_id, p[0])
            put_string(at + 8, p[1])
            put_string(at + 16, p[2])
            put("Ii", at + 24, p[3], -1)
            put("10f", at + 32, *p[4:14])
        for i, l in enumerate(self.lights):
            at = offsets[2] + 80 * i
            next_id = next(ids)
            put("II", at, next_id, l[0])
            put_string(at + 8, l[1])
            put("13f", at + 16, *l[3])
            put("I", at + 68, l[2])
        for i, sp in enumerate(self.spawns):
            at = offsets[3] + 48 * i
            next_id = next(ids)
            put("I", at, next_id)
            put_string(at + 4, sp[0])
            put_string(at + 12, sp[1])
            put("4f", at + 20, *sp[2:6])
        for i, t in enumerate(self.triggers):  # (name, kind, payload, flags, cx, cy, cz, sx, sy, sz, yaw)
            at = offsets[4] + 64 * i
            next_id = next(ids)
            put("I", at, next_id)
            put_string(at + 4, t[0])
            put_string(at + 12, t[1])
            put_string(at + 20, t[2])
            put("7f", at + 28, *t[4:11])
            put("I", at + 56, t[3])
        for i, l in enumerate(self.links):  # (sx, sy, sz, ex, ey, ez, radius, flags, area, userId)
            at = offsets[6] + 48 * i
            put("7f", at, *l[0:7])
            put("III", at + 28, l[7], l[8], l[9])
        first = 0
        for i, a in enumerate(self.areas):
            at = offsets[5] + 32 * i
            put("IffII", at, a[0], a[1], a[2], len(a[3]) // 2, first)
            put("%df" % len(a[3]), polygons_at + 8 * first, *a[3])
            first += len(a[3]) // 2
        for i, r in enumerate(self.rules):
            at = offsets[7] + 24 * i
            put_string(at, r[0])
            put("III", at + 8, r[1], r[2], r[3])
        for i, c in enumerate(self.sizes):
            at = offsets[8] + 32 * i
            put_string(at, c[0])
            put("3f", at + 8, c[1], c[2], c[3])
        s = self.settings
        put("7fI", settings_at, s["navCellSize"], s["navCellHeight"], s["navTileSize"], s["navMaxSlope"],
            s["occlusionCellSize"], s["occlusionYMin"], s["occlusionYMax"], s["occlusionEnabled"])
        put("3f", settings_at + 32, *s["sun"])
        put("3f", settings_at + 44, *s["sunColor"])
        put("3f", settings_at + 56, s["exposure"], s["envSpecular"], s["envDiffuse"])
        put_string(settings_at + 68, s["environment"])
        put("f", settings_at + 76, s["gridStep"])
        file_size = self.align(strings_at + len(self.strings))
        put("IHH", 0, 0x4C564C4A, 1, 0)
        put("9I", 8, *counts)
        put("I", 44, next_id + 1)
        put("9Q", 48, *offsets)
        put("QQQQQ", 120, settings_at, polygons_at, strings_at, len(self.strings), file_size)
        out += self.strings
        out += bytes(file_size - len(out))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(out)
        return len(out)


def build_level(src, out_dir):
    b = Builder()
    pieces = Pieces(b, pack_dir(src, "dungeon"))
    rows, cols = len(LAYOUT), len(LAYOUT[0])

    def solid(cx, cz):
        return cx < 0 or cz < 0 or cx >= cols or cz >= rows or LAYOUT[cz][cx] == "#"

    level = JLevel()
    for cz in range(rows):
        for cx in range(cols):
            c = LAYOUT[cz][cx]
            if c == "#":
                continue
            x, z = cx * CELL + CELL / 2, cz * CELL + CELL / 2
            floor = "floor_tile_big_grate" if c == "G" else ("floor_tile_large_rocks" if (cx * 7 + cz * 3) % 11 == 0 else "floor_tile_large")
            pieces.place(floor, "floor", x, z)
            if c == "P":
                pieces.place("pillar", "wall", x, z)
            elif c == "B":
                pieces.place("barrel_large", "prop", x - 0.8, z + 0.6)
                pieces.place("barrel_small_stack", "prop", x + 1.1, z - 0.9, 30)
            elif c == "C":
                pieces.place("crates_stacked", "prop", x, z, 15)
            elif c == "T":
                pieces.place("table_long", "prop", x, z, 90)
                pieces.place("candle_triple", "decor", x + 0.6, z, 0, 1.0)
                level.lights.append((1, "candles", L_FLICKER, [x + 0.6, 1.6, z, 0, -1, 0, 1.0, 0.7, 0.4, 6, 7, 0.4, 0.6]))
            elif c == "K":
                pieces.place("chest", "prop", x, z + 1.0, 180)
            elif c == "R":
                pieces.place("rubble_half", "prop", x, z, 45)
            elif c == "H":
                level.spawns.append(("party", "party", x, 0.0, z, 0.0))
            elif c == "S":
                level.spawns.append(("skeleton", "enemy", x, 0.0, z, 0.0))
            # Walls on edges to rock; a door frame where a doorway cell meets rock on two sides.
            for dx, dz, deg in ((0, -1, 0), (0, 1, 180), (-1, 0, 90), (1, 0, 270)):
                if not solid(cx + dx, cz + dz):
                    continue
                wx, wz = x + dx * CELL / 2, z + dz * CELL / 2
                name = "wall" if (cx * 5 + cz * 3 + dx + dz) % 6 else "wall_cracked"
                pieces.place(name, "wall", wx, wz, deg)
                # Torches on every third north-facing wall, as lights.
                if dz == -1 and (cx + cz) % 3 == 0:
                    pieces.place("torch_mounted", "decor", wx, wz + 0.5, 0, 2.2)
                    level.lights.append((1, "torch", L_FLICKER, [wx, 2.8, wz + 0.9, 0, -1, 0, 1.0, 0.55, 0.25, 8, 9, 0.4, 0.6]))
            if c == "D":
                horizontal = not solid(cx - 1, cz) and not solid(cx + 1, cz)
                pieces.place("wall_doorway", "wall", x, z, 90 if horizontal else 0)
    # Pillars hide wall seams at grid corners where walls meet.
    for vz in range(rows + 1):
        for vx in range(cols + 1):
            around = [solid(vx - 1, vz - 1), solid(vx, vz - 1), solid(vx - 1, vz), solid(vx, vz)]
            n = around.count(True)
            # Corners (one or three rock cells) and diagonal pinches; straight runs meet cleanly.
            if n in (1, 3) or (n == 2 and around[0] == around[3]):
                pieces.place("pillar", "wall", vx * CELL, vz * CELL)
    size = b.write(os.path.join(out_dir, "levels", "dungeon.glb"))

    # The level: placements of the library's prefabs, by layer.
    flags = {"floor": P_STATIC, "wall": P_STATIC | P_CASTS_SHADOW | P_FADE_OCCLUDER,
             "prop": P_STATIC | P_CASTS_SHADOW, "decor": P_STATIC | P_CASTS_SHADOW}
    for name, layer, x, y, z, deg in pieces.placements:
        q = yaw(deg)
        level.placements.append((level.prefab("levels/dungeon.jscene", name), name, layer, flags[layer],
                                 x, y, z, q[0], q[1], q[2], q[3], 1.0, 1.0, 1.0))
    level.rules = [("floor*", WALKABLE, 1, 0),
                   ("wall", BLOCKS_MOVEMENT | BLOCKS_LOS | BLOCKS_PROJECTILE | CAMERA_FADE, 2, 0),
                   ("prop", BLOCKS_MOVEMENT | BLOCKS_PROJECTILE, 3, 0),
                   ("*", 0, 0, 0)]
    level.sizes = [("medium", 0.4, 1.8, 0.4)]
    # The grates in the west room are difficult terrain (area 5, cost x2 in the demo's filter).
    level.areas = [(5, -1.0, 1.0, [12, 40, 20, 40, 20, 48, 12, 48])]
    level.settings.update({"navCellSize": 0.2, "navCellHeight": 0.1, "navTileSize": 32.0,
                           "occlusionCellSize": 0.5, "occlusionYMin": 0.5, "occlusionYMax": 2.0,
                           "sun": [-0.35, -0.85, -0.4], "sunColor": [0.22, 0.27, 0.4],
                           "exposure": 1.1, "envSpecular": 0.15, "envDiffuse": 0.12, "gridStep": 0.5})
    level_size = level.write(os.path.join(out_dir, "levels", "crypt.jlevel"))
    return size, len(b.j["nodes"]), len(b.j["meshes"]), level_size, len(level.placements)


# --- characters -------------------------------------------------------------------------------

def strip_character(src_path, out_path):
    """Copies a character without animations; everything else (skin, nodes, materials) kept."""
    j, bin_ = read_glb(src_path)
    b = Builder()
    acc, mats = {}, {}
    mesh_map = {}
    for i, n in enumerate(j["nodes"]):
        out = {k: n[k] for k in ("name", "translation", "rotation", "scale", "children") if k in n}
        if "mesh" in n:
            if n["mesh"] not in mesh_map:
                mesh_map[n["mesh"]] = b.mesh(j, bin_, n["mesh"], acc, mats)
            out["mesh"] = mesh_map[n["mesh"]]
        if "skin" in n:
            out["skin"] = n["skin"]
        b.j["nodes"].append(out)
    b.j["scenes"] = [{"nodes": list(j["scenes"][j.get("scene", 0)]["nodes"])}]
    if "skins" in j:
        skins = []
        for s in j["skins"]:
            t = {"joints": s["joints"]}
            if "skeleton" in s:
                t["skeleton"] = s["skeleton"]
            if "inverseBindMatrices" in s:
                t["inverseBindMatrices"] = b.copy_accessor(j, bin_, s["inverseBindMatrices"], acc)
            skins.append(t)
        b.j["skins"] = skins
    return b.write(out_path)


# Clips the demo plays (TASKS M12-07), per rig.
HERO_CLIPS = ["Idle", "Walking_A", "Running_A", "1H_Melee_Attack_Chop", "2H_Melee_Attack_Chop",
              "Dualwield_Melee_Attack_Stab", "Spellcast_Shoot", "Spellcast_Long", "Hit_A",
              "Death_A", "Cheer"]
SKELETON_CLIPS = ["Idle", "Idle_Combat", "Walking_D_Skeletons", "Running_A",
                  "1H_Melee_Attack_Chop", "2H_Melee_Attack_Chop", "Spellcast_Shoot", "Hit_A",
                  "Death_A", "Skeletons_Awaken_Standing", "Taunt"]

COMPONENTS = {"SCALAR": 1, "VEC3": 3, "VEC4": 4}


def read_floats(j, bin_, a):
    """An accessor's float values as tuples."""
    acc = j["accessors"][a]
    if acc["componentType"] != 5126 or "sparse" in acc or acc.get("normalized"):
        raise ValueError("animation accessor %d is not plain float" % a)
    n = COMPONENTS[acc["type"]]
    view = j["bufferViews"][acc["bufferView"]]
    stride = view.get("byteStride", 4 * n)
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    return [struct.unpack_from("<%df" % n, bin_, start + i * stride) for i in range(acc["count"])]


def write_floats(b, values, type_, cache, minmax=False):
    key = (type_, tuple(values))
    if key in cache:
        return cache[key]
    data = b"".join(struct.pack("<%df" % len(v), *v) for v in values)
    acc = {"bufferView": b.view(data), "componentType": 5126, "count": len(values), "type": type_}
    if minmax:
        acc["min"] = [min(v[k] for v in values) for k in range(len(values[0]))]
        acc["max"] = [max(v[k] for v in values) for k in range(len(values[0]))]
    b.j["accessors"].append(acc)
    cache[key] = len(b.j["accessors"]) - 1
    return cache[key]


def write_animation_library(src_path, out_path, clips):
    """The rig of a character (nodes only: no meshes, skins or textures) and the named clips."""
    j, bin_ = read_glb(src_path)
    b = Builder()
    for n in j["nodes"]:
        b.j["nodes"].append({k: n[k] for k in ("name", "translation", "rotation", "scale",
                                                "children") if k in n})
    b.j["scenes"] = [{"nodes": list(j["scenes"][j.get("scene", 0)]["nodes"])}]
    by_name = {a["name"]: a for a in j.get("animations", [])}
    missing = [c for c in clips if c not in by_name]
    if missing:
        raise ValueError("%s has no clips %s" % (src_path, missing))
    cache = {}
    keys = kept = 0
    animations = []
    for name in clips:
        a = by_name[name]
        samplers, channels = [], []
        for ch in a["channels"]:
            sm = a["samplers"][ch["sampler"]]
            interpolation = sm.get("interpolation", "LINEAR")
            times = read_floats(j, bin_, sm["input"])
            values = read_floats(j, bin_, sm["output"])
            keys += len(times)
            if interpolation != "CUBICSPLINE" and all(v == values[0] for v in values):
                times, values = times[:1], values[:1]  # constant: one key holds it
            kept += len(times)
            out_type = j["accessors"][sm["output"]]["type"]
            samplers.append({"input": write_floats(b, times, "SCALAR", cache, True),
                             "output": write_floats(b, values, out_type, cache),
                             "interpolation": interpolation})
            channels.append({"sampler": len(samplers) - 1, "target": dict(ch["target"])})
        animations.append({"name": name, "samplers": samplers, "channels": channels})
    b.j["animations"] = animations
    for key in ("meshes",):
        if not b.j[key]:
            del b.j[key]
    size = b.write(out_path)
    return size, len(clips), keys, kept


LICENSE = """KayKit content in this folder (levels/dungeon.glb, characters/*.glb and
characters/anims/*.glb) is built by samples/iso-demo/tools/kaykit.py from:

  KayKit : Dungeon Remastered 1.0
  KayKit : Character Pack : Adventurers 1.0
  KayKit : Character Pack : Skeletons 1.0

Created and distributed by Kay Lousberg (www.kaylousberg.com).
License: Creative Commons Zero (CC0 1.0), http://creativecommons.org/publicdomain/zero/1.0/
Free to use in personal, educational and commercial projects; crediting is appreciated, not
required. Thank you, Kay!
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--src", default=os.path.join(HERE, "..", "build", "kaykit-src"))
    args = ap.parse_args()
    src = os.path.abspath(args.src)
    fetch(src)
    out = os.path.abspath(ASSETS)
    size, nodes, meshes, level_size, placements = build_level(src, out)
    print(f"levels/dungeon.glb: {size} bytes, {nodes} nodes, {meshes} meshes")
    print(f"levels/crypt.jlevel: {level_size} bytes, {placements} placements")
    for stale in ("crypt.glb", "crypt.physics.json", "crypt.spawns.json"):
        path = os.path.join(out, "levels", stale)
        if os.path.exists(path):
            os.remove(path)
    heroes = {"knight": "Knight", "barbarian": "Barbarian", "mage": "Mage", "rogue": "Rogue",
              "rogue_hooded": "Rogue_Hooded"}
    for out_name, src_name in heroes.items():
        n = strip_character(os.path.join(pack_dir(src, "heroes"), src_name + ".glb"),
                            os.path.join(out, "characters", out_name + ".glb"))
        print(f"characters/{out_name}.glb: {n} bytes")
    for kind in ("Warrior", "Minion", "Mage", "Rogue"):
        n = strip_character(os.path.join(pack_dir(src, "skeletons"), f"Skeleton_{kind}.glb"),
                            os.path.join(out, "characters", f"skeleton_{kind.lower()}.glb"))
        print(f"characters/skeleton_{kind.lower()}.glb: {n} bytes")
    for rig, path, clips in (
            ("heroes", os.path.join(pack_dir(src, "heroes"), "Knight.glb"), HERO_CLIPS),
            ("skeletons", os.path.join(pack_dir(src, "skeletons"), "Skeleton_Warrior.glb"),
             SKELETON_CLIPS)):
        size, count, keys, kept = write_animation_library(
            path, os.path.join(out, "characters", "anims", rig + ".glb"), clips)
        print(f"characters/anims/{rig}.glb: {size} bytes, {count} clips, {kept} of {keys} keys")
    with open(os.path.join(out, "levels", "KAYKIT-LICENSE.txt"), "w", newline="\n") as f:
        f.write(LICENSE)
    return 0


if __name__ == "__main__":
    sys.exit(main())
