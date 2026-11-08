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
     defines it.

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

ENV_BLOCK_RE = re.compile(r"^env:\n((?:[ \t]+.*\n|\n)*)", re.MULTILINE)
ENV_LINE_RE = re.compile(r"""^\s+(SOMCP_[A-Z_]+):\s*(.+?)\s*$""")


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
        keys = re.findall(r"^\s*(SOMCP_[A-Z_]+):", text, re.MULTILINE)
        dupes = {k for k in keys if keys.count(k) > 1}
        for d in sorted(dupes):
            problems.append(f"{name}: {d} is defined more than once")

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
