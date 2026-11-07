#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Copyright (C) 2026 bilieebiliee1-design
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
#
"""Export built Blutter runners as *on-demand* engine packages.

Why this exists
---------------
The built-in runner has to ship inside the APK's jniLibs. A runner is per-Dart
version (about 37 MB unstripped / ~12 MB stripped), so packing several versions
into one APK is not practical.

But a runner does not have to live in the APK: it can be downloaded at runtime
into the app's **private** directory and `dlopen`'d by absolute path. Android's
restriction covers locations other parties can tamper with (external storage
etc.) — the app's own `files/` directory is loadable. SoMCP's native bridge
supports both forms (`lib<name>.so` by library name, or an absolute path).

This script takes the same build output that `build-blutter-runners.sh`
produces and lays it out for that on-demand flow:

    dist/
      dartvm_3.11.5.so         one runner per Dart version
      dartvm_3.12.2.so
      runners.local.json       consumed directly by BlutterEngineStore
      engines-manifest.json    publish/download manifest (sha256 + size)

The layout mirrors myfler/fler-dart's `files/engines/` so artifacts can be
cross-checked between the two projects.

Usage
-----
    python3 tools/blutter-matrix/export_engines.py \
        --manifest build/blutter-matrix/staging/runners.json \
        --jni-root build/blutter-matrix/staging/jniLibs \
        --out dist/blutter-engines
"""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import sys
import time


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def slug(text: str) -> str:
    """Make a filesystem-safe token out of a Dart version.

    Beta/other-channel versions look like "3.13.0 (build 3.13.0-282.3.beta)",
    which cannot be a file name. The real version string is preserved in
    runners.local.json, so the file name only has to be unique and stable.
    """
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", text or "unknown").strip("_")
    return cleaned or "unknown"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, help="built runners.json (staging)")
    parser.add_argument("--jni-root", required=True, help="directory holding libblutter_*.so")
    parser.add_argument("--out", required=True, help="output directory for the engine pack")
    parser.add_argument("--pack-version", default="", help="label recorded in the pack manifest")
    parser.add_argument(
        "--keep-unmatched",
        action="store_true",
        help="do not fail when a manifest runner has no matching .so",
    )
    args = parser.parse_args()

    manifest_path = pathlib.Path(args.manifest)
    jni_root = pathlib.Path(args.jni_root)
    out = pathlib.Path(args.out)
    if not manifest_path.is_file():
        print(f"error: manifest not found: {manifest_path}", file=sys.stderr)
        return 2
    if not jni_root.is_dir():
        print(f"error: jni root not found: {jni_root}", file=sys.stderr)
        return 2

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    runners = manifest.get("runners") or []
    if not runners:
        print("error: manifest declares no runners", file=sys.stderr)
        return 2

    out.mkdir(parents=True, exist_ok=True)
    local_entries = []
    engines = []
    missing = []

    for runner in runners:
        library_name = runner.get("libraryName") or ""
        source = jni_root / f"lib{library_name}.so"
        if not source.is_file():
            missing.append(library_name)
            continue

        dart_version = runner.get("dartVersion") or ""
        file_name = f"dartvm_{slug(dart_version)}.so"
        target = out / file_name
        shutil.copy2(source, target)
        checksum = sha256_of(target)
        size = target.stat().st_size

        # BlutterEngineStore reads this shape directly. libraryName is the bare
        # file name: the install path is unknown at packaging time, and the
        # store resolves it against its own root (then verifies the sha256).
        local_entries.append(
            {
                "runnerId": runner.get("runnerId"),
                "dartVersion": dart_version,
                "engineRevision": runner.get("engineRevision"),
                "abi": runner.get("abi"),
                "compressedPointers": runner.get("compressedPointers"),
                "analysis": runner.get("analysis"),
                "sha256": checksum,
                "source": "local",
                "libraryName": file_name,
                "upstreamCommit": runner.get("upstreamCommit", ""),
                "dartRevision": runner.get("dartRevision"),
                "snapshotAliases": runner.get("snapshotAliases") or [],
                "engineRevisions": runner.get("engineRevisions") or [],
                "flutterVersions": runner.get("flutterVersions") or [],
                "snapshotHashes": runner.get("snapshotHashes") or [],
            }
        )
        engines.append(
            {
                "dartVersion": dart_version,
                "engineRevision": runner.get("engineRevision"),
                "file": file_name,
                "sha256": checksum,
                "sizeBytes": size,
            }
        )
        print(f"  → {file_name}  ({size / 1048576:.1f} MB)  dart {dart_version}")

    if missing and not args.keep_unmatched:
        print(
            "error: no .so found for runners: " + ", ".join(missing) + " (use --keep-unmatched to ignore)",
            file=sys.stderr,
        )
        return 3
    if not engines:
        print("error: nothing exported", file=sys.stderr)
        return 3

    (out / "runners.local.json").write_text(
        json.dumps({"runners": local_entries}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    pack = {
        "packVersion": args.pack_version or time.strftime("%Y.%m.%d"),
        "generatedAt": int(time.time() * 1000),
        "targetAbi": manifest.get("targetAbi", "arm64-v8a"),
        "engineCount": len(engines),
        "totalBytes": sum(item["sizeBytes"] for item in engines),
        "engines": sorted(engines, key=lambda item: item["dartVersion"]),
        "howToInstall": (
            "Copy every dartvm_*.so plus runners.local.json into the app's private "
            "files/blutter-engines/ directory (or use flutter_blutter action=engines to see the path)."
        ),
    }
    (out / "engines-manifest.json").write_text(
        json.dumps(pack, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(
        f"[export-engines] {len(engines)} engine(s), "
        f"{pack['totalBytes'] / 1048576:.1f} MB total → {out}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
