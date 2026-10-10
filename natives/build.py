#!/usr/bin/env python3
"""Builds Jeddy's native libraries for one platform (DESIGN §6.2, TASKS M0-07).

Every library in natives/versions.json is cloned at its pinned tag (the commit is verified),
configured and built with CMake as a Release shared library, then collected into
<out>/<platform>/ together with its license and a natives.json manifest:

    {"platform": "linux-x64", "natives_version": 1,
     "libraries": [{"name": "SDL3", "file": "libSDL3.so.0", "sha256": "...", "runtime": true}, ...]}

The manifest's library order is the load order (dependencies first). A zip of the platform
directory is written next to it for publishing as a GitHub release asset.

Usage:
    python natives/build.py [--platform linux-x64|windows-x64] [--work DIR] [--out DIR]
                            [--only SDL3,zstd] [--jobs N] [--cmake-extra LIB=ARG ...]

Linux builds set RPATH=$ORIGIN and strip symbols. Windows builds use the static MSVC runtime
(/MT) so users don't need the Visual C++ redistributable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform as host_platform
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def detect_platform() -> str:
    machine = host_platform.machine().lower()
    if machine not in ("x86_64", "amd64"):
        sys.exit(f"Unsupported architecture '{machine}': Jeddy targets x64 only")
    if sys.platform.startswith("linux"):
        return "linux-x64"
    if sys.platform.startswith("win"):
        return "windows-x64"
    sys.exit(f"Unsupported OS '{sys.platform}': Jeddy targets Windows and Linux")


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("+ " + " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(lib: dict, src_root: Path) -> Path:
    """Shallow-clones the pinned tag once and verifies the commit."""
    dest = src_root / lib["name"]
    if not (dest / ".git").exists():
        run(["git", "clone", "--quiet", "--depth", "1", "--branch", lib["tag"], lib["repo"], str(dest)])
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=dest, check=True,
                          capture_output=True, text=True).stdout.strip()
    if head != lib["commit"]:
        sys.exit(f"{lib['name']}: {lib['tag']} resolved to {head}, expected {lib['commit']}")
    return dest


def cmake_common(plat: str, install_prefix: Path, prefix_path: list[Path]) -> list[str]:
    args = [
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_INSTALL_PREFIX={install_prefix}",
        "-DCMAKE_POLICY_DEFAULT_CMP0091=NEW",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
    ]
    if prefix_path:
        args.append("-DCMAKE_PREFIX_PATH=" + ";".join(str(p) for p in prefix_path))
    if plat == "linux-x64":
        args += [
            "-G", "Ninja",
            "-DCMAKE_INSTALL_RPATH=$ORIGIN",
            "-DCMAKE_BUILD_WITH_INSTALL_RPATH=ON",
        ]
    else:
        args += ["-A", "x64", "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded"]
    return args


def find_output(install: Path, build: Path, name: str) -> Path:
    for root in (install / "bin", install / "lib", install / "lib64", build):
        for candidate in sorted(root.rglob(name)) if root.exists() else []:
            return candidate
    sys.exit(f"Built library '{name}' not found under {install} or {build}")


def build_library(lib: dict, plat: str, src: Path, work: Path, installs: dict[str, Path], jobs: int,
                  user_extra: list[str]) -> Path:
    build_dir = work / "build" / lib["name"]
    install_dir = work / "install" / lib["name"]
    if lib.get("cmake_source") == "shim":
        source_dir = HERE / "shim"
        extra = [f"-DMIKKTSPACE_DIR={src}"]
    else:
        source_dir = src / lib.get("source_subdir", "")
        extra = []
    prefix_path = [installs[d] for d in lib.get("depends", [])]
    run(["cmake", "-S", str(source_dir), "-B", str(build_dir)]
        + cmake_common(plat, install_dir, prefix_path) + lib["cmake"] + extra + user_extra)
    run(["cmake", "--build", str(build_dir), "--config", "Release", "--parallel", str(jobs)])
    run(["cmake", "--install", str(build_dir), "--config", "Release"])
    return install_dir


def collect(lib: dict, plat: str, src: Path, work: Path, out_dir: Path) -> list[dict]:
    entries = []
    for name in lib["files"][plat]:
        built = find_output(work / "install" / lib["name"], work / "build" / lib["name"], name)
        target = out_dir / name
        shutil.copyfile(built.resolve(), target)  # follow soname symlinks, store as the soname
        if plat == "linux-x64":
            run(["strip", "--strip-unneeded", str(target)])
        entries.append({
            "name": lib["name"],
            "file": name,
            "sha256": sha256(target),
            "runtime": lib["runtime"],
            "depends": lib.get("depends", []),
            "version": lib["tag"],
        })
    license_src = src / lib["license"]
    licenses = out_dir / "licenses"
    licenses.mkdir(exist_ok=True)
    shutil.copyfile(license_src, licenses / f"{lib['name']}-{Path(lib['license']).name}")
    return entries


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--platform", default=None)
    parser.add_argument("--work", default=str(HERE / "build"))
    parser.add_argument("--out", default=str(HERE / "out"))
    parser.add_argument("--only", default="", help="comma-separated subset of libraries")
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 2)
    parser.add_argument("--cmake-extra", action="append", default=[], metavar="LIB=ARG",
                        help="extra CMake argument for one library, e.g. SDL3=-DSDL_WAYLAND=OFF "
                             "(for local machines missing optional dev packages)")
    args = parser.parse_args()
    extra_by_lib: dict[str, list[str]] = {}
    for item in args.cmake_extra:
        name, _, arg = item.partition("=")
        extra_by_lib.setdefault(name, []).append(arg)

    plat = args.platform or detect_platform()
    config = json.loads((HERE / "versions.json").read_text(encoding="utf-8"))
    work = Path(args.work).resolve()
    out_dir = Path(args.out).resolve() / plat
    only = {n for n in args.only.split(",") if n}
    src_root = work / "src"
    src_root.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    installs: dict[str, Path] = {}
    manifest_libs = []
    manifest_path = out_dir / "natives.json"
    previous = {}
    if manifest_path.exists():
        previous = {e["file"]: e for e in json.loads(manifest_path.read_text())["libraries"]}

    for lib in config["libraries"]:
        if only and lib["name"] not in only:
            # Keep earlier results for libraries not rebuilt this time.
            installs[lib["name"]] = work / "install" / lib["name"]
            manifest_libs += [previous[f] for f in lib["files"][plat] if f in previous]
            continue
        src = fetch(lib, src_root)
        installs[lib["name"]] = build_library(lib, plat, src, work, installs, args.jobs,
                                              extra_by_lib.get(lib["name"], []))
        manifest_libs += collect(lib, plat, src, work, out_dir)

    manifest = {
        "platform": plat,
        "natives_version": config["natives_version"],
        "libraries": manifest_libs,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    zip_path = out_dir.parent / f"natives-{plat}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(out_dir.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(out_dir).as_posix())
    print(f"\nWrote {manifest_path} and {zip_path} ({zip_path.stat().st_size // 1024} KiB)")


if __name__ == "__main__":
    main()
