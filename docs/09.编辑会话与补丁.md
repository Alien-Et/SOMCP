# 09 · 编辑会话与补丁

对应源码：`engine/EngineModels.kt`、`EngineRuntimeEditSession.kt`、
`EngineRuntimeEditPatch.kt`（695 行）、`EngineRuntimeBuild.kt`、`PatchAudit.kt`

## 三层数据模型

```kotlin
data class Workspace(              // 工作区：一个 SO 的完整上下文
    val id: String,
    val source: SoSource,
    val data: ByteArray,           // 当前字节（含已应用补丁）
    val elf: ElfFile,
    val temporary: Boolean,
    val originalSha256: String,
    val analysisInputSource: String,
    val structureRecovery: JSONObject,
    val edits: MutableMap<String, EditSession> = ConcurrentHashMap()
)

data class EditSession(            // 编辑会话：一条独立的修改线
    val id: String,
    val data: ByteArray,
    var revision: Int = 0,
    val patches: MutableList<PatchRecord> = mutableListOf(),
    val snapshots: MutableList<Snapshot> = mutableListOf(),
    val undone: MutableList<PatchRecord> = mutableListOf()
) {
    @Transient val lock: ReentrantLock = ReentrantLock()
}

data class Snapshot(               // 快照：完整字节副本
    val revision: Int,
    val sha256: String,
    val timeMillis: Long,
    val patchCount: Int,
    val dataCopy: ByteArray,
    val id: String = UUID.randomUUID().toString(),
    val protected: Boolean = false
)

data class PatchRecord(            // 补丁记录：可审计、可撤销
    val timeMillis: Long,
    val kind: String,              // "hex" / "asm" / "symbol"
    val locator: String,
    val fileOffset: Int,
    val oldHex: String,
    val newHex: String,
    val asm: String = ""
)
```

**关键设计**：同一个 workspace 可以有多个独立 `EditSession`，互不影响。
这样「方案 A」和「方案 B」可以并行试，最后挑一个 `build_so`。

## 会话操作（`session_history`）

| action | 行为 |
| --- | --- |
| `snapshot` | 创建快照（带 label 可选） |
| `rollback` | 回滚到指定 `snapshotIndex`（-1 = 最新） |
| `undo` | 撤销最近 N 个补丁 |
| `redo` | 重做已撤销的补丁 |
| `reset` | 清空所有补丁，回到打开时的状态 |
| `check` | 完整性检查（sha256、补丁数、快照数） |

### undo / redo 的实现

```kotlin
// undo：从 patches 取出最后 N 个，反向应用 oldHex，移到 undone
// redo：从 undone 取出最后 N 个，重新应用 newHex，移回 patches
```

`undone` 是**栈**语义：undo 一次压一个，redo 一次弹一个。任何新的 edit
都会清空 `undone`（标准编辑器的行为）。

### 快照上限

`SettingsStore.maxSnapshots`（默认 20）。超出时淘汰最旧的**非 protected**
快照。`autoSnapshotBeforeEdit` 为真时，每次编辑前自动建快照。

## 补丁工具

### edit_hex

```text
edit_hex(
  workspaceId="ws_...",
  editSessionId="...",
  locator="so_section:.text",
  edits=[{"offset": 1024, "hex": "1F2003D5", "expectHex": "00000000"}],
  dryRun=false
)
```

- `offset` 是 **section 内**的字节偏移，不是文件偏移
- `expectHex` 是乐观锁：原字节不匹配则拒绝
- `dryRun=true` 只预览不写

### edit_asm

```text
edit_asm(
  locator="so_function:libfoo.so!JNI_OnLoad@0x1ecc",
  edits=[{
    "matchText": "mov w0, #0",
    "writeText": "mov w0, #1",
    "occurrence": 0
  }]
)
```

- 支持 `matchText` / `writeText` 文本匹配
- `occurrence` 指定第几次匹配（0-based，-1 = 最后一个）
- `useRegex` 支持正则
- **补丁长度必须相等**（等长覆盖），不会自动搬移后续代码

### edit_symbol / edit_fix_sections

走 LIEF 后端：

- `edit_symbol` 修改符号名 / 类型 / 可见性
- `edit_fix_sections` 重建 section header 表

## 严格校验（`editStrictValidation`）

开启时（默认）：

- `expectHex` / `matchText` 必须匹配
- 补丁不能跨越 section 边界
- 补丁不能修改 ELF header

关闭时会放宽，但会返回 warning 而不是静默通过。

## 补丁上限

`SettingsStore.maxPatchBytes`（默认 1 MiB）限制单次补丁总字节数，
超出返回 `INVALID_ARGUMENT`。

## 构建导出（`build_so`）

```text
build_so(
  workspaceId="ws_...",
  editSessionId="...",
  outputName="patched.so",
  conflictStrategy="rename",
  writeReport=true,
  writeToWorkDir=false
)
```

返回：

```json
{
  "outputPath": "/storage/emulated/0/Android/data/com.soreverse.mcp/files/patched.so",
  "reportPath": ".../patched.patch-report.json",
  "workDirCopyPath": null,
  "workDirCopy": {"ok": false, "message": "not requested"},
  "writeToWorkDir": false,
  "checksums": {"sha256": "...", "size": 22016},
  "openHint": "Pass any outputPath to so_open, or call build_so (action=list) to enumerate built files."
}
```

### 冲突策略

| 策略 | 行为 |
| --- | --- |
| `rename`（默认） | `patched.so` → `patched-1.so` → `patched-2.so` |
| `overwrite` | 覆盖 |
| `skip` | 存在则跳过 |

### 多输出变体

```text
build_so(outputs=[
  {"outputName": "a.so", "writeToWorkDir": false},
  {"outputName": "b.so", "writePatchReport": true}
])
```

上限 `SettingsStore.defaultBuildVariants`（默认 8）。

### 输出落点

```
/storage/emulated/0/Android/data/com.soreverse.mcp/files/
```

App 外部私有目录，**不需要任何存储权限**，不污染用户可见目录。

### patch report

`writePatchReport=true` 时生成 JSON sidecar：

```json
{
  "sessionId": "...",
  "workspaceId": "...",
  "revision": 5,
  "sha256Before": "...",
  "sha256After": "...",
  "diffRangeCount": 3,
  "diffRanges": [...],
  "beforeSections": 27,
  "afterSections": 27,
  "beforeSymbols": 1234,
  "afterSymbols": 1234,
  "sizeBefore": 22016,
  "sizeAfter": 22016
}
```

## 审计持久化

`SettingsStore.auditPersist=true` 时，`session_audit` 的结果会落盘到：

```
/storage/emulated/0/Android/data/com.soreverse.mcp/files/audits/
```

上限 `SettingsStore.maxAudits`（默认 50）。

## 事务模式（批量流水线）

```text
meta_info(action=batch, transactional=true, steps=[...])
```

执行前自动为涉及的 `workspaceId + editSessionId` 建快照；
任何一步失败则自动 rollback 到事务前快照。

## 并发

每个 `EditSession` 有 `ReentrantLock`。所有读写都通过：

```kotlin
withSession(workspaceId, editSessionId, onMissingWorkspace, onMissingSession) {
    session -> /* 已持锁 */
}
```

`revision` 字段是乐观锁：并发修改时旧 revision 的请求会被拒绝。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 undo/redo 的栈语义与事务模式 |
