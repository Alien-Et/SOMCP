# 04 · 引擎运行时

对应源码：`engine/EngineRuntime.kt`、`engine/NativeSoEngine.kt`、
`core/EngineProvider.kt`、以及 20+ 个 `EngineRuntimeXxx.kt` 扩展文件

## 结构

`EngineRuntime` 是**单一门面类**，所有能力都以**扩展函数**的形式分散在多个
文件里。这样拆分的理由：既能按职责分文件保持可读性，又不需要在 40 个文件
之间来回传参。

```kotlin
internal class EngineRuntime(internal val context: Context) {
    // 后端实例
    internal val lief = LiefEngine()
    internal val xanso = XAnSoEngine(context)
    internal val unidbg = UnidbgEmulator(context)
    internal val frida = FridaBridge(context)
    internal val blutter = BlutterCoordinator(context)

    // 工作目录（SAF，魔改后基本不用）
    internal var workDir: WorkDirectory? = null
    internal var workDirUri: Uri? = null

    // 源列表与缓存
    internal var sources: List<SoSource> = emptyList()
    internal var sourceFingerprint: List<FileFingerprint> = emptyList()
    internal val sourceSummaryCache = ConcurrentHashMap<String, SourceSummary>()
    internal val workspaceBySourceKey = ConcurrentHashMap<String, String>()

    // 运行时状态
    internal val pageStore = PageStore()
    internal val emulatorSessions = ConcurrentHashMap<String, EmulatorSession>()
    internal val searchCache = ConcurrentHashMap<String, List<JSONObject>>()
    internal val workspaces = ConcurrentHashMap<String, Workspace>()
}
```

## 文件职责划分

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `EngineRuntime.kt` | ~200 | 门面本体、`guarded` 错误映射、`withSession` |
| `EngineRuntimeSources.kt` | 1,156 | 源解析、`so_open` / `apk_analyze` / `open_url` |
| `EngineRuntimeRead.kt` | 1,768 | 读取、反汇编、搜索、hexdump、分页 |
| `EngineRuntimeEditSession.kt` | — | 编辑会话生命周期 |
| `EngineRuntimeEditPatch.kt` | 695 | hex/asm/symbol 补丁 |
| `EngineRuntimeBuild.kt` | ~380 | `build_so`、patch report |
| `EngineRuntimeAudit.kt` | — | 审计日志持久化 |
| `EngineRuntimeReports.kt` | — | 报告生成与落盘 |
| `EngineRuntimeApkAudit.kt` | ~130 | 4 个 `apk_*` 审计工具 |
| `EngineRuntimeBlutter.kt` | — | Flutter 网关 |
| `EngineRuntimeDynamic.kt` | — | 动态分析分发 |
| `EngineRuntimeBackendRizin.kt` | 1,266 | Rizin 网关 |
| `EngineRuntimeBackendLief.kt` | 1,174 | LIEF 网关 |
| `EngineRuntimeBackendUnidbg.kt` | 1,433 | Unidbg 网关 |
| `EngineJson.kt` / `EngineModels.kt` | — | JSON 与数据模型 |

## guarded：统一的错误映射

所有对外扩展函数都用 `guarded { }` 包裹：

```kotlin
internal fun guarded(block: () -> JSONObject): JSONObject = try {
    block()
} catch (error: CancellationException) {
    throw error                      // 取消要透传，不能吞
} catch (error: Exception) {
    AppLog.e("Tool failed", error)
    val message = error.message ?: "Tool failed"
    when {
        message.startsWith("Workspace not found") -> err("WORKSPACE_NOT_FOUND", ...)
        message.startsWith("SO path not found") -> err("SO_NOT_FOUND", ...)
        message.startsWith("NOT_ELF_INPUT") -> err("NOT_ELF_INPUT", ...)
        error is InsufficientMemoryException -> err("INSUFFICIENT_MEMORY", message)
        error is java.io.IOException -> err("IO_ERROR", message)
        else -> err("ELF_CORRUPTED", message)
    }
}
```

约定：**内部抛异常时用 `error("CODE: 详情")` 的格式**，`guarded` 负责把
`CODE` 映射到结构化错误码。新增错误码时，在 `when` 里加一条分支，
并同步 `18-errors-and-dirs.md`。

## 工作区（Workspace）

```kotlin
internal data class Workspace(
    val id: String,
    val source: SoSource,
    val data: ByteArray,          // 当前 SO 字节
    val elf: ElfFile,             // 解析后的 ELF
    val edits: MutableMap<String, EditSession>
)
```

- `so_open` 创建，`so_close` 销毁
- `workspaceBySourceKey` 做去重：同一个源文件重复 open 会返回已有 workspaceId
- 工作区里的 `data` 是**当前版本**（含已应用的补丁），原始字节在编辑会话里

## 编辑会话（EditSession）

```kotlin
internal class EditSession(
    var data: ByteArray,
    val snapshots: MutableList<Snapshot>,
    val patches: MutableList<PatchRecord>,
    val undone: MutableList<PatchRecord>,
    val lock: ReentrantLock
)
```

详见 `09-edit-session.md`。

## 分页存储（PageStore）

大结果（反汇编列表、函数列表、字符串列表）不一次性返回，而是存进
`PageStore` 并返回游标：

```text
read_disasm(...) → {"items": [...50 条], "pagination": {"nextCursor": "page:50"}}
meta_info(action=continue, cursor="page:50") → 下 50 条
```

`PageStore` 是**有界的**，超出容量会淘汰最旧的条目。`INVALID_CURSOR`
表示游标过期。

## 搜索缓存

`searchCache` 缓存同一个 workspace + pattern 的搜索结果，避免重复扫描。
`so_close` 时会清理。

## EngineProvider

```kotlin
object EngineProvider {
    fun get(context: Context): NativeSoEngine = ...
    fun restoreWorkDirectory(context: Context) { ... }
}
```

单例持有 `NativeSoEngine`，后者是 `EngineRuntime` 的薄包装（对外暴露
`NativeSoEngine.xxx()` 方法，内部直接调 `runtime.xxx()`）。这样 MCP 层
拿到的类型更稳定。

## NativeEngine 后端接缝

`nativecore/NativeEngine.kt` 是**纯接口**，`mcp/` 与 `engine/` 只依赖它：

```kotlin
interface NativeEngine {
    fun disassemble(...): String
    fun assemble(...): ByteArray
    fun analyze(...): JSONObject
    // ...
}
```

- 活跃后端通过 `NativeEngine.active` 解析
- 运行时可用 `NativeEngine.select(backend)` 切换
- `SettingsStore.nativeBackend` 决定初始后端

这是**依赖倒置的单一接缝**：换后端不需要动任何调用点。

## 内存与并发约定

| 项 | 约定 |
| --- | --- |
| `MemoryGuard.ensureAnalysisMemory` | **no-op**（本分支改动）。真实保护靠 `largeHeap` + 堆余量预检 |
| 大 APK | 走流式（`analyzeFile` / `analyzeRootStream`），不进堆 |
| 大 SO | `readBytes` 整块进堆（ELF 解析需要随机访问），受 `maxReadBytes` 限制 |
| 并发 | 重型工具由 `McpHttpServer.heavyGate` 串行化 |
| 编辑会话 | 每个 session 有 `ReentrantLock`，`withSession { }` 保证互斥 |

`largeHeap = true` 在 `AndroidManifest.xml` 里开启，设备实测
`dalvik.vm.heapsize = 512m`。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 MemoryGuard 为 no-op 的事实 |
