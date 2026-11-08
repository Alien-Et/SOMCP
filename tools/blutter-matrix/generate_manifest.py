import argparse
import datetime
import json
import pathlib
import shutil


def version_key(runner):
    """Sort key: newest Dart version first, unknown versions last.

    Dart versions look like ``3.12.2`` or ``3.13.0 (build 3.13.0-282.3.beta)``.
    Only the leading dotted numeric triple participates; the parenthesised
    build suffix is a tie-breaker so the two beta builds stay deterministic.
    Numeric parts are negated so a plain ascending ``sorted`` puts the newest
    version first (tuples cannot be reversed individually).
    """
    raw = runner.get("dartVersion") or ""
    head, _, tail = raw.partition(" (")
    parts = []
    for chunk in head.split("."):
        digits = ""
        for char in chunk:
            if not char.isdigit():
                break
            digits += char
        parts.append(int(digits) if digits else -1)
    while len(parts) < 3:
        parts.append(-1)
    return (tuple(-part for part in parts), tail)


def is_beta(runner):
    """Beta builds carry a parenthesised build suffix in dartVersion."""
    return " (build " in (runner.get("dartVersion") or "")


def select_runners(verified, max_runners, preferred_keys, preferred_versions):
    """Trim the verified set down to the runners that get installed.

    ``max_runners <= 0`` keeps everything (the historical behaviour). Otherwise
    the survivors are picked by this priority, highest first:

    1. compatibilityKey already shipped by the APK - byte-identical rebuild.
    2. dartVersion already shipped - the key changes whenever the pinned
       blutter commit moves (it is part of the hash), so the version is the
       stable identity across a rebase.
    3. stable channel over beta.
    4. newest Dart version.

    The point is that a cap must never silently swap the shipped stable runner
    for a beta one just because the beta sorts higher. Everything dropped is
    still built and staged under build/blutter-matrix, so blutter-engines.yml
    can publish it as an on-demand engine pack - it simply stays out of the
    APK, which is what keeps :app:packageDebug from deflating ~400 MB of
    runners through zipflinger and running out of heap.
    """
    if max_runners <= 0 or len(verified) <= max_runners:
        return list(verified), []

    def rank(runner):
        return (
            0 if runner["compatibilityKey"] in preferred_keys else 1,
            0 if runner.get("dartVersion") in preferred_versions else 1,
            1 if is_beta(runner) else 0,
            version_key(runner),
        )

    ordered = sorted(verified, key=rank)
    kept = ordered[:max_runners]
    dropped = ordered[max_runners:]
    # Preserve plan order for the survivors so the manifest stays stable.
    kept.sort(key=lambda item: item["runnerId"])
    return kept, dropped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", default="build/blutter-matrix/build-plan.json")
    parser.add_argument("--root", default="build/blutter-matrix")
    parser.add_argument("--manifest", default="app/src/main/assets/blutter/runners.json")
    parser.add_argument("--jni-root", default="app/src/main/jniLibs")
    parser.add_argument(
        "--max-runners",
        type=int,
        default=0,
        help="Install at most N runners (0 = unlimited, the historical behaviour)",
    )
    parser.add_argument(
        "--prefer-manifest",
        default="",
        help="Manifest whose existing runners are kept first when --max-runners "
             "trims the set (typically the one currently shipped). Matches on "
             "compatibilityKey, then dartVersion - the key embeds the pinned "
             "blutter commit, so it changes on every upstream rebase.",
    )
    args = parser.parse_args()
    plan = json.loads(pathlib.Path(args.plan).read_text(encoding="utf-8"))
    root = pathlib.Path(args.root)
    native_directory = pathlib.Path(args.jni_root) / "arm64-v8a"
    native_directory.mkdir(parents=True, exist_ok=True)
    for stale in native_directory.glob("libblutter_*.so"):
        stale.unlink()

    preferred_keys = set()
    preferred_versions = set()
    if args.prefer_manifest and pathlib.Path(args.prefer_manifest).is_file():
        try:
            previous = json.loads(pathlib.Path(args.prefer_manifest).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            previous = {}
        for item in previous.get("runners") or []:
            key = item.get("compatibilityKey")
            if isinstance(key, str) and key:
                preferred_keys.add(key)
            version = item.get("dartVersion")
            if isinstance(version, str) and version:
                preferred_versions.add(version)

    verified = []
    for candidate in plan["runners"]:
        status_file = root / "runners" / candidate["runnerId"] / "build.json"
        smoke_file = root / "runners" / candidate["runnerId"] / "smoke.json"
        if not status_file.is_file():
            continue
        status = json.loads(status_file.read_text(encoding="utf-8"))
        smoke = json.loads(smoke_file.read_text(encoding="utf-8")) if smoke_file.is_file() else {"smokeStatus": "not_run"}
        if status.get("buildStatus") != "passed" or status.get("staticStatus") != "passed" or smoke.get("smokeStatus") == "failed":
            continue
        runner = dict(candidate)
        runner.update({"sha256": status["sha256"], "buildStatus": "passed", "staticStatus": "passed", "smokeStatus": smoke.get("smokeStatus", "not_run"), "protocolVersion": 1, "upstreamCommit": plan["blutterCommit"], "exports": ["blutter_run_fd"]})
        runner["engineRevision"] = runner.get("engineRevisions", [None])[0]
        verified.append(runner)

    runners, dropped = select_runners(verified, args.max_runners, preferred_keys, preferred_versions)
    for runner in runners:
        binary = root / "runners" / runner["runnerId"] / f"lib{runner['libraryName']}.so"
        destination = native_directory / binary.name
        shutil.copy2(binary, destination)

    # A runner that built fine but lost the packaging budget is NOT the same as
    # one that failed to build: coverage rows must say so, otherwise the app
    # tells the user "runner_not_built" for a version that is available as an
    # on-demand engine pack.
    dropped_keys = {item["compatibilityKey"] for item in dropped}
    coverage = []
    supported_keys = {item["compatibilityKey"] for item in runners}
    for item in plan["coverage"]:
        row = dict(item)
        matched = [key for key in row.get("compatibilityKeys", []) if key in supported_keys]
        row["supported"] = bool(matched)
        if matched:
            row["reason"] = None
        elif dropped_keys.intersection(row.get("compatibilityKeys") or []):
            row["reason"] = "runner_not_packaged"
        else:
            row["reason"] = row.get("reason", "runner_not_static_verified")
        coverage.append(row)
    manifest = {"schemaVersion": 2, "matrixVersion": plan["matrixVersion"], "protocolVersion": 1, "upstreamCommit": plan["blutterCommit"], "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"), "targetAbi": plan["targetAbi"], "runners": runners, "coverage": coverage}
    pathlib.Path(args.manifest).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if dropped:
        print(f"install budget: kept {len(runners)} of {len(verified)} verified runner(s)")
        for runner in dropped:
            print(f"  - not packaged: {runner['runnerId']} [{runner['compatibilityKey']}]")


if __name__ == "__main__":
    main()
