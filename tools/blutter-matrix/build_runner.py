# SPDX-License-Identifier: AGPL-3.0-or-later

# Copyright (C) 2026 bilieebiliee1-design

# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.

# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
#
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys


UPSTREAM_COMMIT = "4a60ac648bf448c5a7596437243bcd0b9376fdf0"


def package_version(value):
    # CMake package names / `-D<var>` identifiers cannot contain spaces or
    # parentheses. Beta/other-channel Dart SDK versions look like
    # "3.13.0 (build 3.13.0-282.3.beta)", so normalize to a CMake-safe slug
    # (same convention as the runnerId in prepare_build_plan.py). This is only
    # used for the transient dartvm CMake package identity; the friendly
    # runner["dartVersion"] is still kept for curated matching and the manifest.
    return re.sub(r"[^A-Za-z0-9]+", "_", value or "unknown").strip("_").lower()


def run(command, cwd, log):
    result = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"command failed with exit code {result.returncode}: {' '.join(command)}")


def checkout(repository, revision, destination, log):
    if not (destination / ".git").is_dir():
        run(["git", "clone", "--filter=blob:none", repository, str(destination)], destination.parent, log)
    run(["git", "fetch", "origin", revision, "--depth", "1"], destination, log)
    run(["git", "checkout", "--detach", "--force", revision], destination, log)
    run(["git", "reset", "--hard", revision], destination, log)


def write_status(path, status, reason=None, **fields):
    payload = {"status": status, "reason": reason, **fields}
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


IMPORTLIB_SHIM = """import importlib.util
import importlib.machinery

def load_source(modname, filename):
    loader = importlib.machinery.SourceFileLoader(modname, filename)
    spec = importlib.util.spec_from_file_location(modname, filename, loader=loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module
"""


def patch_legacy_dart_tooling(dart):
    # Dart SDKs from the 2.x / early 3.x era still `import imp` in
    # tools/utils.py. Python 3.12 removed that stdlib module
    # (https://docs.python.org/3.12/whatsnew/3.12.html#imp), so
    # make_version.py / dartvm_create_srclist.py die with
    # `ModuleNotFoundError: No module named 'imp'` before cmake ever runs.
    #
    # This mirrors upstream blutter's dartvm_fetch_build.py fix and is a no-op
    # on SDK revisions that never used imp. It also silences the invalid
    # escape-sequence SyntaxWarnings that newer Python turns into hard errors.
    utils_path = dart / "tools" / "utils.py"
    if not utils_path.is_file():
        return
    content = utils_path.read_text(encoding="utf-8")
    if "import imp\n" not in content:
        return
    content = content.replace(" ' awk ", " r' awk ")
    content = content.replace("match_against('", "match_against(r'")
    content = content.replace("re.search('", "re.search(r'")
    content = content.replace("import imp\n", IMPORTLIB_SHIM)
    content = content.replace("imp.load_source", "load_source")
    utils_path.write_text(content, encoding="utf-8")


def make_version_flags(dart):
    # Dart switched make_version.py's argparse options from underscore spellings
    # (--no_git_hash / --no_sdk_hash, pre-3.0 era) to hyphenated ones
    # (--no-git-hash / --no-sdk-hash). Probe the source instead of guessing a
    # version cutoff so every pinned SDK revision keeps working.
    script = (dart / "tools" / "make_version.py").read_text(encoding="utf-8")
    if "--no_git_hash" in script:
        return ["--no_git_hash", "--no_sdk_hash"]
    return ["--no-git-hash", "--no-sdk-hash"]


def prepare_dart_project(dart, blutter, runner, log):
    patch_legacy_dart_tooling(dart)
    tidy = dart / "runtime" / "tools" / "run_clang_tidy.dart"
    content = tidy.read_text(encoding="utf-8")
    marker = content.find("-std=c++")
    cpp_standard = "17" if marker < 0 else content[marker + 8:marker + 10]
    template = (blutter / "scripts" / "CMakeLists.txt").read_text(encoding="utf-8")
    generated = template.replace("VERSION_PLACE_HOLDER", package_version(runner["dartVersion"])).replace("CXX_STD_PLACE_HOLDER", cpp_standard)
    generated = generated.replace('set(CMAKE_INSTALL_PREFIX "${PROJECT_SOURCE_DIR}/../../packages" CACHE PATH "" FORCE)\n', "")
    generated = generated.replace(" PUBLIC dl pthread ${ICU_LIBRARIES}", " PUBLIC dl ${ICU_LIBRARIES} ${ICU_DATA_LIBRARY}")
    (dart / "CMakeLists.txt").write_text(generated, encoding="utf-8")
    (dart / "Config.cmake.in").write_text('@PACKAGE_INIT@\n\ninclude ( "${CMAKE_CURRENT_LIST_DIR}/dartvmTarget.cmake" )\n', encoding="utf-8")
    run([sys.executable, str(blutter / "scripts" / "dartvm_create_srclist.py"), str(dart)], blutter, log)
    version_output = dart / "runtime" / "vm" / "version.cc"
    run([sys.executable, str(dart / "tools" / "make_version.py"), "--input", str(dart / "runtime" / "vm" / "version_in.cc"), "--output", str(version_output), *make_version_flags(dart)], dart, log)
    source_list = dart / "sourcelist.cmake"
    source_text = source_list.read_text(encoding="utf-8")
    if "runtime/vm/version.cc" not in source_text:
        source_list.write_text(source_text.replace("set(SRCS", "set(SRCS\n    ./runtime/vm/version.cc", 1), encoding="utf-8")


def dart_compatibility_definitions(dart_version, packages, package):
    # Mirrors blutter.py's find_compat_macro(). Every macro below gates an
    # #ifdef in blutter/src, so a macro that is never defined does not fail
    # loudly: it silently selects the branch written for a different Dart
    # generation. A 2.19.6 runner that was missing HAS_TYPE_REF compiled the
    # Dart 3.5+ accessor `untag()->owner()` and died with
    # "no member named 'owner' in 'dart::UntaggedTypeParameter'".
    # Probe the pinned headers instead of hard-coding a version table.
    include = packages / "include" / f"dartvm{package}" / "vm"

    def read(name):
        path = include / name
        return path.read_bytes() if path.is_file() else None

    definitions = []
    class_id = read("class_id.h")
    if class_id is not None:
        # Rename the default implementation classes of Map and Set
        # https://github.com/dart-lang/sdk/commit/a2de36e708b8a8e15d3bd49eef2cede57e649436
        if b"V(LinkedHashMap)" in class_id:
            definitions.append("OLD_MAP_SET_NAME")
            # Add immutable maps and sets
            # https://github.com/dart-lang/sdk/commit/e8e9e1d15216788d4112e40f4408c52455d11113
            if b"V(ImmutableLinkedHashMap)" not in class_id:
                definitions.append("OLD_MAP_NO_IMMUTABLE")
        if b" kLastInternalOnlyCid " not in class_id:
            definitions.append("NO_LAST_INTERNAL_ONLY_CID")
        # Remove TypeRef
        # https://github.com/dart-lang/sdk/commit/2ee6fcf5148c34906c04c2ac518077c23891cd1b
        if b"V(TypeRef)" in class_id:
            definitions.append("HAS_TYPE_REF")
        # RecordType landed in Dart 3.0 and was still incomplete in the 2.19 tree.
        if dart_version.startswith("3.") and b"V(RecordType)" in class_id:
            definitions.append("HAS_RECORD_TYPE")
    class_table = read("class_table.h")
    if class_table is not None:
        # Merge ClassTable and SharedClassTable back together
        # https://github.com/dart-lang/sdk/commit/4a4eedd860a8af2b1cb27e68d9feae5550d0f511
        if b"class SharedClassTable {" in class_table:
            definitions.append("HAS_SHARED_CLASS_TABLE")
    stub_code_list = read("stub_code_list.h")
    if stub_code_list is not None:
        # Add InitLateStaticField and InitLateFinalStaticField stub
        # https://github.com/dart-lang/sdk/commit/37d45743e11970f0eacc0ec864e97891347185f5
        if b"V(InitLateStaticField)" not in stub_code_list:
            definitions.append("NO_INIT_LATE_STATIC_FIELD")
    object_store = read("object_store.h")
    if object_store is not None:
        # [vm] Simplify and optimize method extractors
        if b"build_generic_method_extractor_code)" not in object_store:
            definitions.append("NO_METHOD_EXTRACTOR_STUB")
    object_header = read("object.h")
    if object_header is not None:
        # [vm] Refactor access to Integer value
        # https://github.com/dart-lang/sdk/commit/84fd647969f0d74ab63f0994d95b5fc26cac006a
        if b"AsTruncatedInt64Value()" not in object_header:
            definitions.append("UNIFORM_INTEGER_ACCESS")
    return definitions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", default="build/blutter-matrix/build-plan.json")
    parser.add_argument("--runner-id", required=True)
    parser.add_argument("--root", default="build/blutter-matrix")
    parser.add_argument("--blutter-repository", default="https://github.com/worawit/blutter.git")
    parser.add_argument("--dart-repository", default="https://github.com/dart-lang/sdk.git")
    parser.add_argument("--android-ndk", default=os.environ.get("ANDROID_NDK_ROOT", ""))
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--ninja", default="ninja")
    parser.add_argument("--icu-root", default=os.environ.get("ANDROID_ICU_ROOT", ""))
    parser.add_argument("--capstone-root", default=os.environ.get("ANDROID_CAPSTONE_ROOT", ""))
    args = parser.parse_args()
    plan = json.loads(pathlib.Path(args.plan).read_text(encoding="utf-8"))
    runner = next((item for item in plan["runners"] if item["runnerId"] == args.runner_id), None)
    if runner is None:
        raise SystemExit(f"runner not found: {args.runner_id}")
    root = pathlib.Path(args.root).resolve()
    output = root / "runners" / args.runner_id
    status = output / "build.json"
    log_path = output / "build.log"
    output.mkdir(parents=True, exist_ok=True)
    (root / "source").mkdir(parents=True, exist_ok=True)
    (root / "packages").mkdir(parents=True, exist_ok=True)
    (root / "dartvm").mkdir(parents=True, exist_ok=True)
    (root / "cmake").mkdir(parents=True, exist_ok=True)
    if runner.get("blutterCommit") != UPSTREAM_COMMIT:
        write_status(status, "failed", "upstream_commit_mismatch", runnerId=args.runner_id)
        raise SystemExit(2)
    if not args.android_ndk or not pathlib.Path(args.android_ndk).is_dir():
        write_status(status, "failed", "android_ndk_unavailable", runnerId=args.runner_id)
        raise SystemExit(2)
    if not args.icu_root or not pathlib.Path(args.icu_root).is_dir():
        write_status(status, "failed", "android_icu_unavailable", runnerId=args.runner_id, buildStatus="failed", smokeStatus="not_run")
        raise SystemExit(2)
    if not args.capstone_root or not pathlib.Path(args.capstone_root).is_dir():
        write_status(status, "failed", "android_capstone_unavailable", runnerId=args.runner_id, buildStatus="failed", smokeStatus="not_run")
        raise SystemExit(2)
    write_status(status, "running", None, runnerId=args.runner_id, libraryName=runner["libraryName"], buildStatus="running", staticStatus="not_run", smokeStatus="not_run")
    with log_path.open("w", encoding="utf-8") as log:
        try:
            source = root / "source"
            dart = source / f"dart-{runner['dartRevision']}"
            blutter = source / f"blutter-{runner['blutterCommit']}"
            checkout(args.dart_repository, runner["dartRevision"], dart, log)
            checkout(args.blutter_repository, runner["blutterCommit"], blutter, log)
            overlay = pathlib.Path(__file__).resolve().parent / "android-runner"
            atomic_ref_shim = overlay / "dart_vm_atomic_ref_compat.h"
            if not atomic_ref_shim.is_file():
                raise RuntimeError("atomic_ref_shim_missing")
            # Local patches applied on top of the pinned upstream commit, in order:
            #   - dart-app-accessors.patch: expose DartApp::Libraries()/Classes().
            #   - dart-single-snapshot.patch: upstream PR #213 (unmerged) so Dart
            #     3.13+ can be built at all. Those versions dropped the VM isolate,
            #     which renames the snapshot symbols and folds
            #     OBJECT_STORE_STUB_CODE_LIST into VM_STUB_CODE_LIST. The patch
            #     self-gates on the BLUTTER_DART_SINGLE_SNAPSHOT define set in
            #     pch.h, so older Dart versions keep the original code path.
            for patch_name in ("dart-app-accessors.patch", "dart-single-snapshot.patch"):
                run(["git", "apply", "--check", str(overlay / patch_name)], blutter, log)
                run(["git", "apply", str(overlay / patch_name)], blutter, log)
            prepare_dart_project(dart, blutter, runner, log)
            packages = root / "packages" / args.runner_id
            if not (overlay / "CMakeLists.txt").is_file():
                raise RuntimeError("android_runner_overlay_missing")
            dart_build = root / "dartvm" / args.runner_id
            runner_build = root / "cmake" / args.runner_id
            toolchain = pathlib.Path(args.android_ndk) / "build" / "cmake" / "android.toolchain.cmake"
            icu_root = pathlib.Path(args.icu_root).resolve()
            icu_include = icu_root / "include"
            icu_lib = icu_root / "lib"
            # Dart SDKs across the pinned range carry CMakeLists that ask for
            # cmake_minimum_required values older than 3.5 (the 2.19 tree asks
            # for 3.0), and CMake 4.x -- which the CI runners ship -- refuses to
            # configure those at all: "Compatibility with CMake < 3.5 has been
            # removed". The escape hatch keeps the old policy behaviour without
            # editing the SDK; it is ignored on CMake versions that predate it.
            # Mirrors build_capstone_android.sh / build-unidbg-native.sh.
            cmake_policy_min = "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
            run([args.cmake, "-S", str(dart), "-B", str(dart_build), "-G", "Ninja", f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DTARGET_OS=android", "-DTARGET_ARCH=arm64", f"-DCOMPRESSED_PTRS={int(runner['compressedPointers'])}", f"-DICU_ROOT={icu_root}", f"-DCMAKE_PREFIX_PATH={icu_root}", f"-DICU_INCLUDE_DIR={icu_include}", f"-DICU_INCLUDE_DIRS={icu_include}", f"-DICU_LIBRARY={icu_lib / 'libicuuc.a'}", f"-DICU_LIBRARIES={icu_lib / 'libicuuc.a'}", f"-DICU_UC_LIBRARY={icu_lib / 'libicuuc.a'}", f"-DICU_UC_LIBRARY_RELEASE={icu_lib / 'libicuuc.a'}", f"-DICU_DATA_LIBRARY={icu_lib / 'libicudata.a'}", f"-DICU_DATA_LIBRARY_RELEASE={icu_lib / 'libicudata.a'}", f"-DCMAKE_CXX_FLAGS=-include {atomic_ref_shim}", f"-DCMAKE_INSTALL_PREFIX={packages}", "-DCMAKE_BUILD_TYPE=Release", cmake_policy_min], dart, log)
            run([args.cmake, "--build", str(dart_build), "--target", "install", "--parallel"], dart, log)
            package = package_version(runner["dartVersion"])
            dart_package = packages / "lib" / "cmake" / f"dartvm{package}_android_arm64"
            dart_package_name = f"dartvm{package}_android_arm64"
            compatibility = [f"-D{item}=ON" for item in dart_compatibility_definitions(runner["dartVersion"], packages, package)]
            run([args.cmake, "-S", str(overlay), "-B", str(runner_build), "-G", "Ninja", f"-DBLUTTER_SOURCE={blutter / 'blutter'}", f"-DDARTLIB={dart_package_name}", f"-D{dart_package_name}_DIR={dart_package}", f"-DCMAKE_PREFIX_PATH={packages}", f"-DCAPSTONE_ROOT={args.capstone_root}", f"-DRUNNER_LIBRARY={runner['libraryName']}", f"-DRUNNER_ID={runner['runnerId']}", f"-DBLUTTER_COMMIT={runner['blutterCommit']}", f"-DCOMPRESSED_POINTERS={int(runner['compressedPointers'])}", f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DCMAKE_BUILD_TYPE=Release", cmake_policy_min, *compatibility], overlay, log)
            run([args.cmake, "--build", str(runner_build), "--parallel"], overlay, log)
            candidates = list(runner_build.rglob(f"lib{runner['libraryName']}.so"))
            if len(candidates) != 1:
                raise RuntimeError("runner_shared_library_missing_or_ambiguous")
            binary = output / candidates[0].name
            shutil.copy2(candidates[0], binary)
            strip = pathlib.Path(args.android_ndk) / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "llvm-strip"
            if strip.is_file():
                run([str(strip), "--strip-unneeded", str(binary)], output, log)
            readelf = pathlib.Path(args.android_ndk) / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "llvm-readelf"
            if not readelf.is_file():
                readelf = pathlib.Path(shutil.which("llvm-readelf") or "llvm-readelf")
            exports = subprocess.run([str(readelf), "-Ws", str(binary)], capture_output=True, text=True, check=True).stdout
            if "blutter_run_fd" not in exports:
                raise RuntimeError("runner_export_missing")
            digest = hashlib.sha256(binary.read_bytes()).hexdigest()
            write_status(status, "built", None, runnerId=args.runner_id, libraryName=runner["libraryName"], sha256=digest, buildStatus="passed", staticStatus="passed", smokeStatus="not_run")
        except Exception as error:
            reason = str(error)
            if "android_runner_overlay_missing" in reason:
                reason = "blutter_compile_error"
            elif "runner_shared_library" in reason or "runner_export" in reason:
                reason = "android_cross_compile_error"
            write_status(status, "failed", reason, runnerId=args.runner_id, buildStatus="failed", staticStatus="failed", smokeStatus="not_run")
            # Surface the captured build log so CI and local runs show the real
            # compiler/toolchain error instead of only the generic cmake/ninja
            # command from the RuntimeError above.
            try:
                with open(log_path, "r", encoding="utf-8", errors="replace") as handle:
                    lines = handle.readlines()
                tail = "".join(lines[-200:])
                sys.stderr.write(f"\n===== [blutter-runners] tail of build log ({len(lines)} total lines): {log_path} =====\n")
                sys.stderr.write(tail)
                if not tail.endswith("\n"):
                    sys.stderr.write("\n")
                sys.stderr.write("===== [blutter-runners] end of build log =====\n")
            except OSError:
                pass
            raise


if __name__ == "__main__":
    main()
