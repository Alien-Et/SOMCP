# 06 · ELF 解析与审计

对应源码：`engine/ElfParser.kt`、`ElfModels.kt`、`ElfAudit.kt`（656 行）、
`ElfOverviewBuilder.kt`（933 行）、`ElfSectionResolver.kt`、`NativeProfile.kt`

## 分层

```
ElfParser（纯 Kotlin，无 native 依赖）
  ↓ 解析出 ElfFile
ElfModels（数据类）
  ↓
ElfAudit（15 项静态审计，纯字节检查）
ElfOverviewBuilder（给 analyze_elf 用的综合视图）
ElfSectionResolver（section 定位辅助）
```

这一层**完全不依赖 native 库**，是 Rizin / LIEF 之外的第一道解析。
即使 `librz_native.so` 缺失（本地 debug 包常见），ELF 基础分析仍然可用。

## ElfFile

```kotlin
data class ElfFile(
    val data: ByteArray,
    val bits: Int,                    // 32 / 64
    val littleEndian: Boolean,
    val type: Int,                    // ET_DYN / ET_EXEC / ...
    val machine: Int,                 // EM_ARM=40 / EM_AARCH64=183 / EM_386=3 / EM_X86_64=62
    val entry: Long,
    val sections: List<SectionInfo>,
    val symbols: List<SymbolInfo>,       // .symtab
    val dynSymbols: List<SymbolInfo>,    // .dynsym
    val relocations: List<RelocInfo>,
    val strings: List<StringInfo>,
    val programHeaders: List<ProgramHeaderInfo>,
    val dynamicEntries: List<DynamicEntryInfo>
) {
    val architecture: String    // "arm32" / "arm64" / "x86" / "x86_64" / "mips" / "unknown"
    val endian: String          // "little" / "big"
    val machineName: String
}
```

## 地址换算

```kotlin
fun vaddrToOffset(elf: ElfFile, vaddr: Long): Long?   // ElfAudit.kt
```

ELF 里 vaddr 与文件偏移不一定相等（`.data.rel.ro` 常有偏差）。所有涉及
「地址 ↔ 文件位置」的操作都必须走这个函数，不能直接用 vaddr 当 offset。

`translate_address` 类的歧义场景：某个数值同时落在某段的 vaddr 范围和另一段的
文件偏移范围时，需要显式指定坐标轴。

## 15 项静态审计

全部是**纯字节检查，不执行目标文件**，对不可信输入安全。

| 工具 | 函数 | 检查内容 |
| --- | --- | --- |
| `elf_abi_profile` | `abiProfile` | ABI 特征、编译器指纹、目标平台 |
| `elf_byte_ledger` | `byteLedger` | 各段字节分布账本（代码/数据/零填充占比） |
| `elf_section_anomaly` | `sectionAnomalies` | 节区异常：重叠、越界、非标准名、权限冲突 |
| `elf_phdr_invariants` | `phdrInvariants` | program header 不变量：段对齐、权限、vaddr 单调性 |
| `elf_dynamic_tags` | `dynamicTags` | DT_* 标签解析与语义 |
| `elf_dt_flags` | `dtFlags` | DT_FLAGS / DT_FLAGS_1 位域解码 |
| `elf_hash_tables` | `hashTables` | `.hash` / `.gnu.hash` 校验 |
| `elf_reloc_census` | `relocCensus` | 重定位类型统计与分布 |
| `dt_needed_resolve` | — | DT_NEEDED 依赖链解析 |
| `symbol_visibility_audit` | — | 符号可见性（导出/隐藏/弱符号） |
| `code_padding_caves` | — | 代码填充区（code cave）识别 |
| `jni_mangle_codec` | `JniMangle` | JNI 符号名编解码（`Java_a_b_c` ↔ `a.b.c`） |
| `android_ident_note` | — | Android 构建标识（NDK 版本、`__ANDROID_API__` 痕迹） |
| `ndk_toolchain_fingerprint` | `NativeProfile` | NDK 工具链指纹 |
| `libcxx_variant` | `NativeProfile` | libc++ vs gnustl vs none |

### 为什么用「证据分级」而不是直接下结论

`elf_section_anomaly` 之类会返回 `severity` + `evidence` 字段，
而不是简单的 `isPacked: true`。理由：正常 NDK Release 包也会有
`.cold.`、`__clang_call_terminate` 这类 clang 产物，用符号名启发式判定
会把它们误报成 OLLVM。

## ElfOverviewBuilder

`analyze_elf` 的综合视图，933 行。返回：

```json
{
  "header": {"class": 64, "endian": "little", "type": "ET_DYN", "machine": "AARCH64", "entry": "0x..."},
  "sections": {"count": 27, "executable": [...], "writable": [...]},
  "symbols": {"symtab": 1234, "dynsym": 567, "exported": 89},
  "relocations": {"total": 4321, "byType": {...}},
  "strings": {"count": 890, "suspicious": [...]},
  "programHeaders": [...],
  "dynamicEntries": [...],
  "capabilities": {
    "canDisassemble": true,
    "canEditAsm": true,
    "canEditHex": true,
    "canResolveRelocs": true,
    "hasPltGot": true,
    "canSearchStrings": true,
    "hasDebugInfo": false,
    "hasEhFrame": true
  },
  "checksums": {"sha256": "...", "size": 22016}
}
```

`capabilities` 让 AI 客户端知道这个 SO 能做什么，避免调用不支持的操作。

## 字符串提取

`StringInfo` 带 `encoding` 与 `confidence`：

```kotlin
data class StringInfo(
    val offset: Long,
    val value: String,
    val length: Int,
    val section: String,
    val encoding: String = "UTF-8",
    val confidence: Double = 1.0
)
```

UTF-16LE 候选的 confidence 会低于 UTF-8，因为随机字节撞出可读 UTF-16 的
概率不低。`search_strings` 支持 `minConfidence` 过滤。

## ElfSectionResolver

辅助函数，用于：

- 给定 vaddr 找所属 section
- 给定文件偏移找所属 section
- 判断 section 是否可执行
- 计算函数字节范围

`EngineRuntimeRead.kt` 里大量依赖这些函数做 locator 解析。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；列出 15 项审计与其证据分级原则 |
