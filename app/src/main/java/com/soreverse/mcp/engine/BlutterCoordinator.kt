// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Copyright (C) 2026 bilieebiliee1-design
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.
//
package com.soreverse.mcp.engine

import android.content.Context
import com.soreverse.mcp.core.InsufficientMemoryException
import com.soreverse.mcp.core.MemoryGuard
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class BlutterCoordinator(
    private val context: Context,
    private val store: BlutterResultStore = BlutterResultStore(context),
    private val registry: BlutterRunnerRegistry = BlutterRunnerRegistry(context)
) {
    private val embedded = BlutterEmbeddedBackend(context, store)
    fun handle(args: JSONObject, workDirectory: WorkDirectory? = null): JSONObject = when (args.str("action", "inspect")) {
        "inspect" -> inspect(args, workDirectory)

        "analyze" -> analyze(args, workDirectory)

        "status" -> status(args.str("jobId"))

        "result" -> result(
            args.str("jobId"),
            args.optString("kind").takeIf {
                it.isNotBlank()
            },
            args.optString("cursor").takeIf { it.isNotBlank() },
            args.optInt("limit", 1000)
        )

        "cancel" -> cancel(args.str("jobId"))

        "packages" -> ok(registry.capabilities())

        "prune" -> ok(store.prune(args.optLong("olderThanMillis", 7L * 24 * 60 * 60 * 1000)))

        else -> err(
            "UNKNOWN_ACTION",
            "Unsupported flutter_blutter action",
            "action",
            args.str("action")
        )
    }

    private fun inspect(args: JSONObject, workDirectory: WorkDirectory?): JSONObject {
        val path = args.str("path")
        if (path.isBlank()) {
            return err(
                "INPUT_REQUIRED",
                "path is required for inspect",
                "path",
                path
            )
        }
        val file = File(path)
        val abi = args.str("abi", "auto")
        // 【修复】root-only 路径（/data/data 私有、权限 000/600）stat 能成功
        // 但 open() 会 EACCES，此时 `isFile` 仍为 true，旧条件 `!isFile` 判不到。
        // 现在：存在但不可读（或压根不是普通文件）时，先流式落到 App 私有缓存。
        val needsStage = !file.isDirectory && (!file.isFile || !file.canRead())
        val staged: File? = if (needsStage && RootShell.isAvailable()) {
            stageFlutterInput(path, File(context.cacheDir, "flutter-inspect").apply { mkdirs() })
        } else {
            null
        }
        val effective = staged ?: file
        return try {
            when {
                effective.isDirectory -> inspectDirectory(effective, path, abi)

                effective.isFile -> {
                    if (!effective.extension.equals("apk", true) &&
                        !effective.extension.equals("zip", true)
                    ) {
                        err(
                            "UNSUPPORTED_INPUT",
                            "inspect currently accepts an APK or a libapp/libflutter directory",
                            "path",
                            path
                        )
                    } else {
                        ok(inspectApkFile(effective, abi))
                    }
                }

                workDirectory != null -> {
                    val declared = workDirectory.fileSize(path) ?: ApkAnalyzer.MAX_INPUT_BYTES
                    MemoryGuard.ensureAnalysisMemory(declared, "Inspecting Flutter APK $path")
                    ok(inspectApkBytes(workDirectory.readFile(path, ApkAnalyzer.MAX_INPUT_BYTES), path, abi))
                }

                else -> err(
                    "INPUT_NOT_FOUND",
                    if (RootShell.isAvailable()) {
                        "Input path does not exist or is not readable through the root channel: $path"
                    } else {
                        "Input path does not exist and no work directory is selected"
                    },
                    "path",
                    path
                )
            }
        } catch (error: InsufficientMemoryException) {
            err(
                "INSUFFICIENT_MEMORY",
                error.message ?: "Insufficient memory to inspect Flutter artifacts",
                "path",
                path
            )
        } catch (error: Exception) {
            err(
                error.message?.substringBefore(':')?.takeIf {
                    it in
                        setOf("INPUT_LIMIT_EXCEEDED", "APK_INVALID", "UNSUPPORTED_ELF", "FLUTTER_LIBS_NOT_FOUND")
                }
                    ?: "FLUTTER_INSPECT_FAILED",
                error.message ?: "Flutter inspection failed",
                "path",
                path
            )
        } finally {
            // 临时副本用完即删
            staged?.let { runCatching { it.delete() } }
        }
    }

    /**
     * Inventory only reads the zip central directory; the fingerprint needs the
     * two libraries, which are streamed into a scratch directory and then mapped
     * read-only. Nothing proportional to the APK ever lands in the heap.
     */
    private fun inspectApkFile(file: File, abi: String): JSONObject {
        val inventory = FlutterArtifactInspector.inspectApk(file, abi)
        if (inventory.optJSONObject("selected") == null) return inventory
        val scratch = File(context.cacheDir, "flutter-inspect/${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            val files = FlutterArtifactInspector.extractLibrariesTo(file, scratch, abi)
            inventory.put("selectedAnalysis", FlutterArtifactInspector.inspectLibraries(files))
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun inspectApkBytes(bytes: ByteArray, path: String, abi: String): JSONObject {
        val inventory = FlutterArtifactInspector.inspectApk(bytes, path, abi)
        if (inventory.optJSONObject("selected") == null) return inventory
        return inventory.put(
            "selectedAnalysis",
            FlutterArtifactInspector.inspectLibraries(
                FlutterArtifactInspector.extractLibraries(bytes, path, abi)
            )
        )
    }

    private fun analyze(args: JSONObject, workDirectory: WorkDirectory?): JSONObject {
        val jobId = store.create(args)
        val prepared = runCatching { prepareInput(args, workDirectory, jobId) }
        if (prepared.isFailure) {
            runCatching { store.jobInput(jobId).deleteRecursively() }
            val failure = prepared.exceptionOrNull()
            val problem = JSONObject()
                .put("code", resolutionCode(failure))
                .put(
                    "message",
                    failure?.message ?: "Cannot resolve Flutter libraries"
                )
                .put("recoverable", false)
                .put("stage", "resolving_input")
            store.update(jobId, "failed", "resolving_input", problem)
            return ok(JSONObject().put("jobId", jobId).put("status", "failed").put("error", problem))
        }
        val input = prepared.getOrThrow()
        val analysis = input.analysis
        val flutter = analysis.optJSONObject("flutter") ?: JSONObject()
        val requirement = BlutterRunnerRequirement(
            engineRevision = flutter.optJSONArray("engineIds")?.optString(0)?.takeIf {
                it.isNotBlank()
            },
            dartVersion = flutter.optString("dartVersion").takeIf { it.isNotBlank() },
            abi = analysis.optString("abi", args.str("abi", "arm64-v8a")),
            compressedPointers = flutter.optBoolean("compressedPointers", false),
            analysis = !args.optBoolean("noAnalysis", false),
            snapshotHash = flutter.optString("snapshotHash").takeIf { it.isNotBlank() }
        )
        val runner = registry.select(requirement)
        if (runner == null) {
            input.discard()
            return unsupportedRunner(jobId, requirement, analysis)
        }
        val key = embedded.cacheKey(input.files, runner.sha256, args.toString())
        val cached = runCatching { store.lookup(key) }
        if (cached.isFailure) {
            input.discard()
            val failure = cached.exceptionOrNull()
            val problem = JSONObject().put("code", resolutionCode(failure)).put(
                "message",
                failure?.message ?: "Cannot read the cached Blutter result"
            ).put("recoverable", true).put("stage", "cache_lookup")
            store.update(jobId, "failed", "cache_lookup", problem)
            return ok(JSONObject().put("jobId", jobId).put("status", "failed").put("error", problem))
        }
        cached.getOrNull()?.let { hit ->
            input.discard()
            store.update(jobId, "running", "cache_lookup")
            store.commit(jobId, hit, key)
            return ok(
                JSONObject().put("jobId", jobId).put("status", "succeeded").put("backend", "cache")
                    .put("cacheHit", true).put("runner", runner.toJson())
                    .put("message", "Reused identical previous analysis result (cache hit)")
            )
        }
        val startResult = runCatching { embedded.start(jobId, runner, input.files, args) }
        if (startResult.isFailure) {
            input.discard()
            val problem = JSONObject().put("code", "RUNNER_START_FAILED").put(
                "message",
                startResult.exceptionOrNull()?.message ?: "Cannot start Blutter runner"
            ).put("recoverable", false).put("stage", "runner_execution")
            store.update(jobId, "failed", "runner_execution", problem)
            return ok(JSONObject().put("jobId", jobId).put("status", "failed").put("error", problem))
        }
        return ok(
            JSONObject().put(
                "jobId",
                jobId
            ).put(
                "status",
                "running"
            ).put("backend", "embedded").put("runner", runner.toJson())
        )
    }

    /**
     * Rejects the fingerprint with the manifest's own words: the supported range
     * is read from the runner matrix instead of being hard-coded here, and the
     * coverage rows that mention this fingerprint tell the caller which Flutter
     * build to rebuild with.
     */
    private fun unsupportedRunner(jobId: String, requirement: BlutterRunnerRequirement, analysis: JSONObject): JSONObject {
        val supported = registry.supportedSummary()
        val required = JSONObject()
            .put("engineRevision", requirement.engineRevision ?: JSONObject.NULL)
            .put("dartVersion", requirement.dartVersion ?: JSONObject.NULL)
            .put("snapshotHash", requirement.snapshotHash ?: JSONObject.NULL)
            .put("abi", requirement.abi)
            .put("compressedPointers", requirement.compressedPointers)
            .put("analysis", requirement.analysis)
        val range = describeSupport(supported)
        val error = JSONObject()
            .put("code", "FLUTTER_VERSION_NOT_SUPPORTED")
            .put(
                "message",
                "This release embeds only the $range Blutter runner. The target APK does not match that snapshot compatibility key."
            )
            .put("recoverable", false)
            .put("stage", "runner_selection")
            .put("supportedFlutter", stringList(supported.optJSONArray("flutterVersions")).joinToString("–"))
            .put("supportedDart", stringList(supported.optJSONArray("dartVersions")).joinToString(", "))
            .put("supportedVersions", supported)
            .put("required", required)
            .put("coverageHints", registry.coverageHints(requirement))
        store.update(jobId, "failed", "runner_selection", error)
        return ok(
            JSONObject()
                .put("jobId", jobId)
                .put("status", "failed")
                .put("inspection", analysis)
                .put("requiredRunner", required)
                .put("error", error)
                .put(
                    "nextActions",
                    JSONArray().put("use a Flutter ${stringList(supported.optJSONArray("flutterVersions")).joinToString("–")} APK")
                        .put("inspect the APK fingerprint without running analysis")
                )
        )
    }

    private fun describeSupport(supported: JSONObject): String {
        val flutter = stringList(supported.optJSONArray("flutterVersions"))
        val dart = stringList(supported.optJSONArray("dartVersions"))
        val abis = stringList(supported.optJSONArray("abis"))
        val flutterText = when {
            flutter.isEmpty() -> "no Flutter version"
            flutter.size == 1 -> flutter.first()
            else -> "${flutter.first()}–${flutter.last()}"
        }
        val dartText = if (dart.isEmpty()) "" else " / Dart ${dart.joinToString(", ")}"
        val abiText = if (abis.isEmpty()) "" else " ${abis.joinToString(", ")}"
        return "Flutter $flutterText$dartText$abiText"
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
    }

    private fun status(jobId: String): JSONObject = store.get(jobId)?.let { ok(it) }
        ?: err("JOB_NOT_FOUND", "Blutter job was not found", "jobId", jobId)

    private fun result(jobId: String, kind: String?, cursor: String?, limit: Int): JSONObject = runCatching {
        store.result(jobId, kind, cursor, limit)?.let { ok(it) }
            ?: err("RESULT_NOT_FOUND", "Blutter result is not available", "jobId", jobId)
    }.getOrElse { error ->
        if (error is InsufficientMemoryException) {
            err("INSUFFICIENT_MEMORY", error.message ?: "Insufficient memory to read the result", "jobId", jobId)
        } else {
            err(
                "INVALID_RESULT_REQUEST",
                error.message ?: "Invalid result request",
                "jobId",
                jobId
            )
        }
    }

    private fun cancel(jobId: String): JSONObject {
        embedded.cancel(jobId)
        return if (store.cancel(
                jobId
            )
        ) {
            ok(
                JSONObject().put("jobId", jobId).put("status", "cancelled")
            )
        } else {
            err(
                "JOB_NOT_CANCELLABLE",
                "Job was not found or already finished",
                "jobId",
                jobId
            )
        }
    }

    private class PreparedInput(val files: FlutterLibraryFiles, val analysis: JSONObject) {
        /** Drops the extracted copies; the result cache keeps only the JSON. */
        fun discard() {
            runCatching { files.libapp.parentFile?.deleteRecursively() }
        }
    }

    private fun prepareInput(args: JSONObject, workDirectory: WorkDirectory?, jobId: String): PreparedInput {
        val path = args.str("path")
        val abi = args.str("abi", "arm64-v8a")
        val jobInput = store.jobInput(jobId)
        val file = File(path)
        // 【修复】旧实现只认 `File.isDirectory` / `File.isFile`，root-only 路径
        // （/data/data 私有、权限 000/600）stat 成功但 open() 会 EACCES，
        // 于是掉进 else 分支强制要求 SAF 工作目录 —— 而魔改版已经把选目录的
        // UI 删了，flutter_blutter 对这类路径永久不可用。
        // 现在：存在但不可读时先流式落到 App 私有缓存，再走本地随机访问路径。
        val needsStage = !file.isDirectory && (!file.isFile || !file.canRead())
        val staged: File? = if (needsStage && RootShell.isAvailable()) {
            stageFlutterInput(path, jobInput)
        } else {
            null
        }
        val effective = staged ?: file
        return try {
            val files = when {
                effective.isDirectory -> libraryFilesFromDirectory(effective, path, abi, jobInput)

                effective.isFile -> {
                    val inventory = FlutterArtifactInspector.inspectApk(effective, abi)
                    if (inventory.optJSONObject("selected") == null) {
                        throw IllegalArgumentException(
                            "FLUTTER_LIBS_NOT_FOUND: APK does not contain a complete libapp.so/libflutter.so pair"
                        )
                    }
                    FlutterArtifactInspector.extractLibrariesTo(effective, jobInput, abi)
                }

                else -> {
                    val dir = workDirectory ?: throw IllegalArgumentException("INPUT_NOT_FOUND: $path")
                    MemoryGuard.ensureAnalysisMemory(
                        dir.fileSize(path) ?: ApkAnalyzer.MAX_INPUT_BYTES,
                        "Analyzing Flutter APK $path"
                    )
                    val bytes = dir.readFile(path, ApkAnalyzer.MAX_INPUT_BYTES)
                    val libraries = FlutterArtifactInspector.extractLibraries(bytes, path, abi)
                    jobInput.mkdirs()
                    val app = File(jobInput, "libapp.so").apply { writeBytes(libraries.libapp) }
                    val flutter = File(jobInput, "libflutter.so").apply { writeBytes(libraries.libflutter) }
                    FlutterArtifactInspector.libraryFiles(app, flutter, libraries.displayName, libraries.abi)
                }
            }
            PreparedInput(files, FlutterArtifactInspector.inspectLibraries(files))
        } finally {
            // 临时副本用完即删，jobInput 里已经有需要的 libapp/libflutter
            staged?.let { runCatching { it.delete() } }
        }
    }

    /** 把 root-only 的 APK 流式落到 jobInput 目录下的临时文件。 */
    private fun stageFlutterInput(path: String, jobInput: File): File? {
        val size = RootShell.fileSize(path) ?: -1L
        val stream = RootShell.openStream(path, size) ?: return null
        return try {
            jobInput.mkdirs()
            val out = File(jobInput, "staged-${System.currentTimeMillis()}-${path.substringAfterLast('/').take(48)}")
            stream.use { input -> out.outputStream().buffered().use { sink -> input.copyTo(sink, 64 * 1024) } }
            out
        } catch (t: Throwable) {
            null
        }
    }

    private fun libraryFilesFromDirectory(dir: File, path: String, abi: String, jobInput: File): FlutterLibraryFiles {
        val app = dir.resolve("libapp.so").takeIf { it.isFile }
            ?: dir.resolve("App").takeIf { it.isFile }
            ?: throw IllegalArgumentException("FLUTTER_LIBS_NOT_FOUND: $path does not contain libapp.so")
        val flutter = dir.resolve("libflutter.so").takeIf { it.isFile }
            ?: dir.resolve("Flutter").takeIf { it.isFile }
            ?: throw IllegalArgumentException("FLUTTER_LIBS_NOT_FOUND: $path does not contain libflutter.so")
        jobInput.mkdirs()
        val appCopy = File(jobInput, "libapp.so")
        val flutterCopy = File(jobInput, "libflutter.so")
        copyFile(app, appCopy)
        copyFile(flutter, flutterCopy)
        return FlutterArtifactInspector.libraryFiles(appCopy, flutterCopy, dir.name, abi)
    }

    private fun copyFile(source: File, target: File) {
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    private fun inspectDirectory(dir: File, path: String, requestedAbi: String): JSONObject {
        val app = dir.resolve("libapp.so").takeIf { it.isFile } ?: dir.resolve("App").takeIf { it.isFile }
        val flutter = dir.resolve("libflutter.so").takeIf { it.isFile }
            ?: dir.resolve("Flutter").takeIf { it.isFile }
        if (app == null ||
            flutter == null
        ) {
            return err(
                "FLUTTER_LIBS_NOT_FOUND",
                "Directory must contain libapp.so and libflutter.so",
                "path",
                path
            )
        }
        val abi = if (requestedAbi == "auto") "arm64-v8a" else requestedAbi
        return ok(
            FlutterArtifactInspector.inspectLibraries(
                FlutterArtifactInspector.libraryFiles(app, flutter, dir.name, abi)
            )
        )
    }

    private fun resolutionCode(failure: Throwable?): String = when {
        failure is InsufficientMemoryException -> "INSUFFICIENT_MEMORY"
        failure?.message?.startsWith("FLUTTER_LIBS_NOT_FOUND") == true -> "FLUTTER_LIBS_NOT_FOUND"
        failure?.message?.startsWith("INPUT_LIMIT_EXCEEDED") == true -> "INPUT_LIMIT_EXCEEDED"
        else -> "INPUT_RESOLUTION_FAILED"
    }
}
