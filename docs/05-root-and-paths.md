# 05 · Root 通道与路径解析

对应源码：`core/RootShell.kt`、`engine/EngineRuntimeSources.kt`（1,156 行）、
`engine/Storage.kt`（729 行）

## 核心问题

Android 上 App 是普通 uid（如 `u0_a380`），读不到：

- `/data/app`（`0711`，能穿过但不能 `ls`）
- `/data/data/<other>`（`0700`，完全封锁）
- `/data/local/tmp`（`0711`，能穿过但文件常是 `root:root 600`）
- `/sdcard`（分区存储，本 App manifest **没有任何存储权限**）

## 解决思路

**有 root 就直读，能直读就别用 root。**

```
优先级 1：File(path).canRead() 为真
          → 直接 FileInputStream，随机访问，解析完整

优先级 2：RootShell.isAvailable() 为真
          → ProcessBuilder("su", "-c", "cat '<path>'")
          → cat 的 stdout 直接接到 App 进程 InputStream
          → 流式解析，不落盘

优先级 3：SAF 工作目录（魔改后基本不用，仅 open_url 保留兼容）
```

## RootShell

`core/RootShell.kt` 是唯一 su 通道，四条能力：

| 方法 | 用途 |
| --- | --- |
| `isAvailable()` | 探测 su（带缓存，`invalidate()` 可清） |
| `exec(cmd)` | 执行 shell，返回 stdout/stderr/exitCode |
| `readBytes(path)` | 一次性读成 ByteArray（需随机访问时用） |
| `openStream(path)` | 打开流（流式解析用） |

### su 探测

```kotlin
private val SU_CANDIDATES = listOf(
    "su", "/system/bin/su", "/system/xbin/su",
    "/sbin/su", "/su/bin/su", "/data/adb/magisk/su"
)

fun isAvailable(): Boolean {
    // 依次试每个候选，跑 `su -c id`，输出含 "uid=0" 且退出码 0 才算成功
}
```

结果缓存在 `cachedAvailable` / `cachedSuPath`，通过 `invalidate()` 清空。
**首页每 30 秒才重测一次**（不是 1 秒），避免一天 86400 次 fork。

### readBytes 的超时与 stderr

旧实现有两个坑，都已修：

```kotlin
// 【修复 1】旧代码算了 timeout 变量却没用，waitFor 用的是 defaultTimeoutMs；
//          而且 waitFor 排在「读完 stdout」之后 —— cat 卡住时 input.read()
//          永久阻塞，永远走不到 waitFor。现在用看门狗强杀。
val timer = java.util.Timer("rootshell-read-watchdog", true)
timer.schedule(object : TimerTask() {
    override fun run() { timedOut = true; proc.destroyForcibly() }
}, timeout)

// 【修复 2】stderr 必须抽干，否则 cat 写满 64KB pipe 缓冲后与 App 互等死锁
val errThread = Thread({ proc.errorStream.bufferedReader().use { /* 抽干 */ } })

// 读 stdout，遇到 IOException 说明被看门狗杀了
while (true) {
    val n = try { input.read(buf) } catch (e: IOException) { timedOut = true; break }
    ...
}
```

### openStream 的退出码校验

```kotlin
fun openStream(path: String, expectedSize: Long = -1L): InputStream? {
    // ...
    object : FilterInputStream(raw) {
        private fun verifyEof() {
            // 读到 EOF 时检查 cat 是否正常退出
            val code = process.exitValue()
            if (code != 0) {
                throw IOException("root cat failed for $path (exit=$code): ${stderr}")
            }
            if (expectedSize >= 0 && consumed != expectedSize) {
                throw IOException("root cat truncated for $path: read $consumed of $expectedSize bytes")
            }
        }
    }
}
```

旧实现不检查退出码，`cat <目录>` / `cat <不存在文件>` 也返回「非 null 但
立即 EOF」的流 —— 上层无法区分「空文件」和「读取失败」，`apk_analyze`
因此对任意文件都返回 `ok:true`。

### shellQuote

```kotlin
fun shellQuote(s: String): String {
    if (s.isEmpty()) return "''"
    return "'" + s.replace("'", "'\\''") + "'"
}
```

POSIX 单引号转义，防注入。所有传给 su 的路径都必须过这个函数。

## 目录扫描

### listFiles

旧实现解析 `ls -la` 的固定 9 列，而 Android toybox 的 `ls -la` 把日期拆成
两列（`2026-11-07 13:56`），导致所有行被跳过，root 扫描永远返回空。

现在改用：

```bash
find <dir> -maxdepth 1 -mindepth 1 -print0 | xargs -0 -r stat -c '%F|%s|%Y|%n'
```

格式由自己控制，跨 ROM 稳定。

### walkFiles

旧实现递归逐层调 `listFiles`，每层起一次 su 进程。扫 `/data/app` 会起几百个
su，慢到 MCP 请求超时。

现在改为**一条命令**：

```bash
find <dir> -maxdepth N -type f \( -iname '*.so' -o ... \) -print0 \
  | xargs -0 -r stat -c '%F|%s|%Y|%n'
```

实测 `/data/app` 下 7725 个 `.so`，用时 **0.12 秒**。

### 扫描根

```kotlin
internal val ROOT_SCAN_ROOTS = listOf(
    "/data/local/tmp",
    "/data/app",
    "/storage/emulated/0",
    "/sdcard"
)
```

最多返回 2000 项，`maxDepth = 6`。

## 源解析（SoSource）

```kotlin
data class SoSource(
    val path: String,
    val source: String,           // "root_fs" / "local_fs" / "build_output" / ...
    val name: String,
    val size: Long,
    val modified: Long,
    val treeDocumentUri: Uri?,    // SAF 用
    val apkPath: String? = null,  // APK 内嵌 SO
    val apkEntry: String? = null,
    val abi: String? = null
)
```

### resolveLocalSoSource 的三段

```kotlin
internal fun EngineRuntime.resolveLocalSoSource(rawPath: String): SoSource? {
    // 1) root 优先：有 root 就任意路径直读
    if (RootShell.isAvailable() && path.startsWith("/")) {
        if (RootShell.exists(path) && !RootShell.isDirectory(path)) {
            return SoSource(path, "root_fs", name, size, mtime, null)
        }
    }

    // 2) 无 root（或 root 未命中）：普通可直读文件
    if (file.exists() && file.isFile) {
        return SoSource(canonical, if (file.canRead()) "local_fs" else "root_fs", ...)
    }

    // 3) app 私有目录下的 .so
    if (canonical.startsWith(extDir) || canonical.startsWith(intDir)) {
        return SoSource(canonical, "build_output", ...)
    }
    return null
}
```

## APK 分析的两条路径

见 `10-apk-analysis.md`。摘要：

| 路径 | 触发条件 | parser 标识 |
| --- | --- | --- |
| `analyzeFile` | `File(path).canRead()` 为真 | `builtin_apk_zip_streaming` |
| `analyzeRootStream` | 不可直读 + root 可用 | `builtin_apk_zip_root_stream` |

## 需要临时副本的工具

只有少数工具确实需要随机访问，它们会落一份临时文件到 **App 私有缓存**：

| 工具 | 落点 | 清理 |
| --- | --- | --- |
| `flutter_blutter`（root-only 路径） | `cache/flutter-inspect/staged-*.apk` | `finally` 删除 |
| 4 个 `apk_*` 审计（root-only 路径） | `cache/apk-audit/audit-*.apk` | `finally` 删除 |
| `UnidbgEmulator.writeTemp` | `cache/unidbg/emu_*.so` | 写前清理 5 分钟前旧文件 |

**都不写 `/sdcard`，不碰被测 App 的目录。**

## 非 root 能用什么

| 能力 | 非 root |
| --- | --- |
| MCP 服务本体 | ✅ |
| `so_open` / `apk_analyze` 直连可读路径 | ✅ `/data/app/<pkg>/lib/<abi>/*.so`、`base.apk`（`644`/`755`） |
| 全部 ELF 分析 | ✅（rizin/LIEF 都在 APK 的 `lib/` 里） |
| `edit_*` / `build_so` | ✅ 输出到 App 外部私有目录 |
| `so_open(action=list)` 自动扫描 | ❌ 4 个扫描根全被拒 |
| 读 `/data/data/<other>` | ❌ 物理无解 |
| 读 `/sdcard` 任意目录 | ❌ manifest 无存储权限 |

非 root 的边界在于**找不到路径**而非读不到文件：`/data/app` 是 `0711`，
不能 `ls` 但知道确切路径就能穿过。可以用 `PackageManager` 拿 `sourceDir` /
`nativeLibraryDir` 来定位（当前代码未实现，见 `19-changelog.md` 的待办）。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 readBytes/openStream 的超时与退出码修复 |
