#!/usr/bin/env python3
"""Builds the iso-demo's CC0 content from KayKit packs by Kay Lousberg (TASKS M10-01).

Fetches three KayKit asset packs (CC0 1.0, https://kaylousberg.com) at pinned commits, then:

* assembles the "crypt" level from Dungeon Remastered pieces laid out on a 4 m grid
  (assets/levels/crypt.glb), with node names the physics bake rules key on
  (floor_*, wall_*, prop_*, decor_*, light_*);
* copies the party (Adventurers) and the enemies (Skeletons) without their 76 animations each
  (the engine has no animation module yet, so characters render in their bind pose), with
  textures embedded (assets/characters/*.glb);
* writes assets/levels/crypt.physics.json (collision rules, navmesh, occlusion) and
  assets/levels/crypt.spawns.json (party, enemies and torch lights for the demo).

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
    """Dungeon pieces loaded once and instanced: (meshes of each scene root) per piece name."""

    def __init__(self, builder, directory):
        self.b = builder
        self.dir = directory
        self.loaded = {}

    def get(self, name):
        if name in self.loaded:
            return self.loaded[name]
        path = os.path.join(self.dir, name + ".gltf.glb")
        if not os.path.exists(path):
            path = os.path.join(self.dir, name + ".glb")
        j, bin_ = read_glb(path)
        acc, mats = {}, {}
        roots = []

        def visit(n):
            src = j["nodes"][n]
            out = {k: src[k] for k in ("translation", "rotation", "scale") if k in src}
            if "mesh" in src:
                out["mesh"] = self.b.mesh(j, bin_, src["mesh"], acc, mats)
            out["children"] = [visit(c) for c in src.get("children", [])]
            return out

        for n in j["scenes"][j.get("scene", 0)]["nodes"]:
            roots.append(visit(n))
        self.loaded[name] = roots
        return roots

    def place(self, name, prefix, x, z, deg=0.0, y=0.0):
        roots = self.get(name)
        index = len(self.b.j["nodes"])
        parent = self.b.node({"name": f"{prefix}_{name}_{index}", "translation": [x, y, z],
                              "rotation": yaw(deg)}, root=True)
        kids = []
        count = [0]

        def emit(tmpl):
            n = {k: tmpl[k] for k in ("translation", "rotation", "scale", "mesh") if k in tmpl}
            n["name"] = f"{prefix}_{name}_{index}_{count[0]}"
            count[0] += 1
            k = self.b.node(n)
            ch = [emit(c) for c in tmpl["children"]]
            if ch:
                self.b.j["nodes"][k]["children"] = ch
            return k

        for r in roots:
            kids.append(emit(r))
        self.b.j["nodes"][parent]["children"] = kids


def build_level(src, out_dir):
    b = Builder()
    pieces = Pieces(b, pack_dir(src, "dungeon"))
    rows, cols = len(LAYOUT), len(LAYOUT[0])

    def solid(cx, cz):
        return cx < 0 or cz < 0 or cx >= cols or cz >= rows or LAYOUT[cz][cx] == "#"

    spawns = {"party": [], "enemies": [], "lights": [], "cell": CELL}
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
                spawns["lights"].append({"x": x + 0.6, "y": 1.6, "z": z, "color": [1.0, 0.7, 0.4], "radius": 7, "intensity": 6})
            elif c == "K":
                pieces.place("chest", "prop", x, z + 1.0, 180)
            elif c == "R":
                pieces.place("rubble_half", "prop", x, z, 45)
            elif c == "H":
                spawns["party"].append([x, 0.0, z])
            elif c == "S":
                spawns["enemies"].append([x, 0.0, z])
            # Walls on edges to rock; a door frame where a doorway cell meets rock on two sides.
            for dx, dz, deg in ((0, -1, 0), (0, 1, 180), (-1, 0, 90), (1, 0, 270)):
                if not solid(cx + dx, cz + dz):
                    continue
                wx, wz = x + dx * CELL / 2, z + dz * CELL / 2
                along = dz != 0
                name = "wall" if (cx * 5 + cz * 3 + dx + dz) % 6 else "wall_cracked"
                pieces.place(name, "wall", wx, wz, deg)
                # Torches on every third north-facing wall, as lights.
                if dz == -1 and (cx + cz) % 3 == 0:
                    pieces.place("torch_mounted", "decor", wx, wz + 0.5, 0, 2.2)
                    spawns["lights"].append({"x": wx, "y": 2.8, "z": wz + 0.9, "color": [1.0, 0.55, 0.25], "radius": 9, "intensity": 8})
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
    size = b.write(os.path.join(out_dir, "levels", "crypt.glb"))
    with open(os.path.join(out_dir, "levels", "crypt.spawns.json"), "w", newline="\n") as f:
        json.dump(spawns, f, indent=1)
        f.write("\n")
    return size, len(b.j["nodes"]), len(b.j["meshes"])


PHYSICS = {
    "scene": "crypt.glb",
    "collision": [
        {"nodes": "floor_*", "flags": ["WALKABLE"], "surface": 1},
        {"nodes": "wall_*", "flags": ["BLOCKS_MOVEMENT", "BLOCKS_LOS", "BLOCKS_PROJECTILE", "CAMERA_FADE"], "surface": 2},
        {"nodes": "prop_*", "flags": ["BLOCKS_MOVEMENT", "BLOCKS_PROJECTILE"], "surface": 3},
        {"nodes": "*", "flags": []},
    ],
    "navmesh": {
        "cellSize": 0.2, "cellHeight": 0.1, "tileSize": 32,
        "sizes": {"medium": {"radius": 0.4, "height": 1.8, "climb": 0.4}},
        # The grates in the west room are difficult terrain (area 5, cost x2 in the demo's filter).
        "areas": [{"polygon": [12, 40, 20, 40, 20, 48, 12, 48], "yMin": -1, "yMax": 1, "area": 5}],
    },
    "occlusion": {"cellSize": 0.5, "yMin": 0.5, "yMax": 2.0},
}


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


LICENSE = """KayKit content in this folder (levels/crypt.glb and characters/*.glb) is built by
samples/iso-demo/tools/kaykit.py from:

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
    size, nodes, meshes = build_level(src, out)
    print(f"levels/crypt.glb: {size} bytes, {nodes} nodes, {meshes} meshes")
    with open(os.path.join(out, "levels", "crypt.physics.json"), "w", newline="\n") as f:
        json.dump(PHYSICS, f, indent=1)
        f.write("\n")
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
    with open(os.path.join(out, "levels", "KAYKIT-LICENSE.txt"), "w", newline="\n") as f:
        f.write(LICENSE)
    return 0


if __name__ == "__main__":
    sys.exit(main())
