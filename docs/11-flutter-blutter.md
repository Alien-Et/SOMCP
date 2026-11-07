# 11 · Flutter / Blutter

对应源码：`engine/FlutterArtifactInspector.kt`（617 行）、`FlutterByteScan.kt`、
`FlutterModels.kt`、`BlutterCoordinator.kt`、`BlutterEmbeddedBackend.kt`、
`BlutterRunnerRegistry.kt`、`BlutterResultStore.kt`、`blutter/`（2 个文件）、
`cpp/blutter_bridge.cpp`

## 两条能力

| action | 作用 | 依赖 |
| --- | --- | --- |
| `inspect` | 识别 Flutter APK、提取版本指纹、列出 assets | 纯 Kotlin |
| `analyze` | 完整 Blutter 分析（Dart 类/方法恢复） | 内置 Runner |

## inspect：清单与指纹

```text
flutter_blutter(action="inspect", path="/path/to/app.apk")
```

流程：

1. 用 `ZipFile` 只读 central directory（不解压任何条目）
2. 找 `lib/<abi>/libapp.so` + `libflutter.so` 配对
3. 选 ABI（`arm64-v8a` > `x86_64` > `armeabi-v7a` > `x86`）
4. 如果有配对，提取两个库到 scratch 目录并计算快照指纹

返回：

```json
{
  "path": "...",
  "flutter": {
    "detected": true,
    "mode": "aot",
    "requestedAbi": "auto",
    "supportedAbis": ["arm64-v8a"],
    "assets": ["assets/flutter_assets/AssetManifest.json", ...],
    "candidates": [{"abi": "arm64-v8a", "libappEntry": "...", "libappSize": 12345678, "complete": true}]
  },
  "selected": {"abi": "arm64-v8a", "libappEntry": "...", "libflutterEntry": "..."},
  "selectedAnalysis": { /* 快照指纹 */ },
  "entryCount": 14473,
  "limitations": [...]
}
```

### 快照指纹

从 `libapp.so` 里读 Dart 快照的元信息：

- `SNAPSHOT_HASH_OFFSET = 20`（Dart 快照头里的 hash 位置）
- `SNAPSHOT_HASH_BYTES = 32`
- `SNAPSHOT_FLAG_BYTES = 288`

`engineRevision` / `dartVersion` / `snapshotHash` / `compressedPointers`
用于匹配兼容的 Blutter Runner。

## 内存策略

Flutter APK 常 100-500 MB，`libapp.so` 单独可 100 MB+，Dart 快照只能作为
**一整块连续 buffer** 反序列化。所以：

| 操作 | 策略 |
| --- | --- |
| APK 清单 | `ZipFile`，只读 central directory |
| 提取库 | 流式写到文件，边写边算 hash，峰值堆 = 1 个 64 KiB buffer |
| ELF 解析 | `ByteView` 对文件做只读 `mmap`，页是 file-backed，内核可回收 |

旧实现是 `File.readBytes()` 整包 + 每个 section `slice()` 成新数组 +
`.rodata` 解码成 String 跑两个正则 —— 200MB 的包约 600MB 瞬时堆。

## root-only 路径支持

旧实现只认 `File.isDirectory` / `File.isFile`。root-only 路径
（`/data/data` 私有、权限 000/600）**stat 成功但 `open()` 会 EACCES**，
此时 `isFile` 仍为 `true`，旧条件判不到，会掉进 else 分支报 EACCES。

现在的判定：

```kotlin
val needsStage = !file.isDirectory && (!file.isFile || !file.canRead())
val staged = if (needsStage && RootShell.isAvailable()) {
    stageFlutterInput(path, File(context.cacheDir, "flutter-inspect").apply { mkdirs() })
} else null
```

`stageFlutterInput` 用 `RootShell.openStream(path, size)` 流式落一份副本到
`cache/flutter-inspect/staged-*.apk`，`finally` 里删除。

## analyze：Blutter Runner

```text
flutter_blutter(action="analyze", path="...", abi="arm64-v8a", noAnalysis=false)
```

流程：

1. `prepareInput` 解析输入（APK / 目录 / root-only）
2. `BlutterRunnerRegistry.select(requirement)` 匹配 Runner
3. `embedded.cacheKey(...)` 查缓存
4. 命中则直接返回，未命中则跑 `BlutterRunnerService`
5. 结果存 `BlutterResultStore`

### Runner 匹配

```kotlin
data class BlutterRunnerRequirement(
    val engineRevision: String?,
    val dartVersion: String?,
    val abi: String,
    val compressedPointers: Boolean,
    val analysis: Boolean,
    val snapshotHash: String?
)
```

内置 Runner 覆盖 **Flutter 3.44.2 – 3.44.7 / Dart 3.12.2 arm64**。
其他版本返回明确的不支持信息，不会瞎跑。

### 异步任务

`analyze` 立即返回 `jobId`，后续用：

```text
flutter_blutter(action="status", jobId="...")
flutter_blutter(action="result", jobId="...", kind="classes|methods|objects", limit=1000, cursor="...")
flutter_blutter(action="cancel", jobId="...")
```

### 结果存储

`BlutterResultStore` 落点在：

```
/data/user/0/com.soreverse.mcp/no_backup/blutter/v1/
  ├── jobs/<jobId>/          # 输入（libapp.so / libflutter.so）
  └── results/<cacheKey>/    # 输出（classes / methods / objects）
```

`no_backup` 不进云备份，不会被系统 `cache` 清理机制清掉。
`flutter_blutter(action="prune")` 可清理过期结果。

### 原生桥

`blutter/` 里两个文件：

| 文件 | 作用 |
| --- | --- |
| `NativeBlutterBridge.kt` | `System.loadLibrary("blutter_bridge")` 的 Kotlin 包装 |
| `BlutterRunnerService.kt` | 前台服务，跑 Blutter 并回写进度 |

`cpp/blutter_bridge.cpp` 导出：

```kotlin
fun run(
    libraryName: String,
    libappFd: Int,
    libflutterFd: Int,
    resultFd: Int,
    optionsJson: String,
    cancellationToken: Long
): Int
```

用 **fd 而不是路径**传参，这样 root-only 的临时副本也能直接喂给 native 层，
不需要额外权限。

## inspectFlutterFromEntries

给流式 root 路径用的轻量版本：调用方已经有条目清单（来自 `ZipInputStream`），
直接基于清单做 Flutter 判定，避免为了一个 `detected` 布尔值再开一次 root 流。

```kotlin
fun inspectFlutterFromEntries(entries: JSONArray, path: String): JSONObject
```

注意：`assets` 列表受 `entryLimit` 限制（只统计返回窗口内的条目）。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 needsStage 判定与 fd 传参设计 |
