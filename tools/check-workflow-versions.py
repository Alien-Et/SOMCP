#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Fail when the GitHub workflows disagree on a shared toolchain version.

The three build workflows (build.yml / release.yml / test-v11.yml) must compile
with the *same* JDK, Gradle, Android platform, build-tools, NDK and cmake. A
checks run that passes with a different toolchain than the release uses proves
nothing about the release, which is exactly the failure mode that let an
incomplete `test-v11.yml` stay green while the release APK was the only one
carrying Unidbg and rz-ghidra natives.

Each workflow therefore declares its versions once, in a top-level `env:` block,
under `SOMCP_*` names, and refers to them by `$SOMCP_*` /
`${{ env.SOMCP_* }}` below. This script enforces both halves:

  1. no hard-coded copy of a pinned value anywhere outside the `env:` block;
  2. the same `SOMCP_*` name carries the same value in every workflow that
     defines it;
  3. every workflow that sets up Gradle seeds the distribution from the mirror
     first (see tools/seed-gradle.sh) — dropping that step silently restores the
     fragile "download from services.gradle.org and hope" path that failed run
     42469477147, and nothing else would notice.

Run from the repository root: python3 tools/check-workflow-versions.py
Exit code 0 = consistent, 1 = problems found.
"""

from __future__ import annotations

import pathlib
import re
import sys

WORKFLOW_DIR = pathlib.Path(".github/workflows")

# Every pinned toolchain version, with the pattern that would betray a
# hard-coded copy in a `run:` line or an action input.
PINNED = {
    "SOMCP_JAVA_VERSION": re.compile(r"""java-version:\s*["']?\d"""),
    "SOMCP_GRADLE_VERSION": re.compile(r"""gradle-version:\s*["']?\d"""),
    "SOMCP_ANDROID_PLATFORM": re.compile(r"""platforms;android-\d+"""),
    "SOMCP_BUILD_TOOLS": re.compile(r"""build-tools;\d"""),
    "SOMCP_NDK": re.compile(r"""ndk;\d"""),
    "SOMCP_CMAKE": re.compile(r"""cmake;\d+\.\d+"""),
    # A mirror base URL written inline somewhere other than the env block would
    # be a second source of truth for where the bytes come from.
    "SOMCP_GRADLE_DIST_MIRROR": re.compile(r"""mirrors\.[a-z0-9.-]+/gradle"""),
    # The pinned official sha256 of the distribution. Repetition of a 64-char
    # hex string outside the env block means somebody pasted it next to a
    # download instead of letting tools/seed-gradle.sh do the verification.
    "SOMCP_GRADLE_DIST_SHA256": re.compile(r"""\b[0-9a-f]{64}\b"""),
}

# Which workflow is expected to pin which version. A workflow that builds no
# APK has no business installing a JDK, Gradle or SDK platform, and pinning one
# there would just be a second value to keep in sync.
EXPECTED = {
    "build.yml": set(PINNED),
    "release.yml": set(PINNED),
    "test-v11.yml": set(PINNED),
    "blutter-engines.yml": {"SOMCP_NDK"},
}

# Workflows that provision Gradle, and the one-line proof that they do it via
# the seeded mirror copy rather than by asking the action to download.
SEED_CALL = 'bash tools/seed-gradle.sh "$SOMCP_GRADLE_VERSION" ' \
            '"$SOMCP_GRADLE_DIST_MIRROR" "$SOMCP_GRADLE_DIST_SHA256"'
NEEDS_GRADLE = ("build.yml", "release.yml", "test-v11.yml")

# The pinned sha256 must be tied to the pinned version, or a version bump would
# ship a hash for the wrong archive. This cannot be verified offline (that
# would mean trusting the same delivery channel twice); tools/seed-gradle.sh
# verifies the real bytes at run time, and this only catches the obvious
# "bumped one, forgot the other" edit.
GRADLE_DIST_VERSIONED_SHA256 = {
    "9.6.1": "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14",
}

ENV_BLOCK_RE = re.compile(r"^env:\n((?:[ \t]+.*\n|\n)*)", re.MULTILINE)
ENV_LINE_RE = re.compile(r"""^\s+(SOMCP_[A-Z0-9_]+):\s*(.+?)\s*$""")


def parse_env(text: str) -> dict[str, str]:
    """Top-level env block only (a nested `env:` inside a step is indented more)."""
    m = ENV_BLOCK_RE.search(text)
    if not m:
        return {}
    out: dict[str, str] = {}
    for line in m.group(1).splitlines():
        lm = ENV_LINE_RE.match(line)
        if lm:
            out[lm.group(1)] = lm.group(2).strip().strip('"').strip("'")
    return out


def strip_comments(text: str) -> str:
    """Drop whole-line YAML comments so prose about a version is not a hit."""
    return "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )


def body_count(text: str, needle: str) -> int:
    """Occurrences of a `run:` line, ignoring the explanatory prose above it."""
    return strip_comments(text).count(needle)


def main() -> int:
    if not WORKFLOW_DIR.is_dir():
        print(f"error: {WORKFLOW_DIR} not found (run from the repository root)")
        return 1

    problems: list[str] = []
    defined: dict[str, dict[str, str]] = {}

    for path in sorted(WORKFLOW_DIR.glob("*.yml")):
        name = path.name
        text = path.read_text(encoding="utf-8")
        env = parse_env(text)
        defined[name] = env

        for key in EXPECTED.get(name, set()):
            if key not in env:
                problems.append(f"{name}: missing {key} in the top-level env block")

        # A SOMCP_* key must not be defined twice in one file.
        keys = re.findall(r"^\s*(SOMCP_[A-Z0-9_]+):", text, re.MULTILINE)
        dupes = {k for k in keys if keys.count(k) > 1}
        for d in sorted(dupes):
            problems.append(f"{name}: {d} is defined more than once")

        if name in NEEDS_GRADLE:
            if body_count(text, SEED_CALL) != 1:
                problems.append(
                    f"{name}: expected exactly one `{SEED_CALL}` step — without it "
                    "Gradle is downloaded from services.gradle.org, which 504s"
                )
            gradle_step = re.search(r"^\s+- name: Set up Gradle\s*$", text, re.MULTILINE)
            if not gradle_step:
                problems.append(f"{name}: the seeded `Set up Gradle` step is missing")
            if re.search(r"^\s+- name: Retry Gradle provisioning", text, re.MULTILINE):
                problems.append(
                    f"{name}: the `Retry Gradle provisioning` workaround is back; "
                    "the mirror seeding replaces it"
                )

        # No hard-coded copy of a pinned value outside comments.
        body = strip_comments(text)
        for key, pattern in PINNED.items():
            if key not in env:
                continue
            value = env[key]
            for m in pattern.finditer(body):
                frag = m.group(0)
                # The env declaration itself is the one legitimate occurrence.
                line_start = body.rfind("\n", 0, m.start()) + 1
                line = body[line_start:body.find("\n", m.start())]
                if line.strip().startswith(f"{key}:"):
                    continue
                problems.append(
                    f"{name}: hard-coded `{frag}` — use ${key} (={value}) instead"
                )

    # Cross-file agreement.
    seen: dict[str, dict[str, str]] = {}
    for name, env in defined.items():
        for key, value in env.items():
            seen.setdefault(key, {})[name] = value
    for key, per_file in sorted(seen.items()):
        distinct = set(per_file.values())
        if len(distinct) > 1:
            detail = ", ".join(f"{f}={v}" for f, v in sorted(per_file.items()))
            problems.append(f"{key} disagrees across workflows: {detail}")

    # The pinned sha256 must belong to the pinned Gradle version. Catching
    # "bumped SOMCP_GRADLE_VERSION, forgot the hash" here is cheap; catching it
    # on a runner is not (the run would die in the seed step).
    dist_hashes = {
        name: env["SOMCP_GRADLE_DIST_SHA256"]
        for name, env in defined.items()
        if "SOMCP_GRADLE_DIST_SHA256" in env
    }
    for name, env in sorted(defined.items()):
        version = env.get("SOMCP_GRADLE_VERSION")
        if version is None or name not in dist_hashes:
            continue
        known = GRADLE_DIST_VERSIONED_SHA256.get(version)
        if known is None:
            problems.append(
                f"{name}: SOMCP_GRADLE_VERSION={version} is not in "
                "GRADLE_DIST_VERSIONED_SHA256 — add it together with the sha256 "
                "read from the official <zip>.sha256 file"
            )
        elif dist_hashes[name] != known:
            problems.append(
                f"{name}: SOMCP_GRADLE_DIST_SHA256 is not the hash of Gradle "
                f"{version} (expected {known}, got {dist_hashes[name]})"
            )

    if problems:
        print("workflow toolchain versions are inconsistent:\n")
        for p in problems:
            print(f"  - {p}")
        print(
            "\nEach build workflow declares its versions once in a top-level "
            "`env:` block and refers to them by $SOMCP_*. See docs/17.构建与CI.md."
        )
        return 1

    total = sum(len(v) for v in defined.values())
    print(f"OK: {len(defined)} workflows, {total} SOMCP_* definitions, all consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
