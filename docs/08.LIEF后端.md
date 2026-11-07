# 08 · LIEF 后端

对应源码：`engine/LiefEngine.kt`、`EngineRuntimeBackendLief.kt`（1,174 行）、
`app/src/main/cpp/lief_elf.cpp`（540 行）

## 能力

| action | 作用 |
| --- | --- |
| `capabilities` | 能力面 |
| `dispatch` | 反射式方法调用 |
| `parse` | 解析 ELF |
| `parse_any` | 解析任意格式（ELF / PE / Mach-O / DEX / ART / OAT / VDEX） |
| `list` | 列 sections / symbols / relocations / strings / imports |
| `patch_address` | 按 VA 打补丁 |
| `add_export` | 添加导出符号 |
| `remove_symbol` | 删除符号 |
| `build` | 重建二进制 |
| `fix_sections` | section header 重建 |
| `report` | 分析报告 |

## 与 Rizin 的分工

| 场景 | 用哪个 |
| --- | --- |
| 反汇编 / 函数分析 / CFG / xref | Rizin |
| ELF 结构修改（section / symbol / export） | LIEF |
| section header 重建（被 strip 的包） | LIEF |
| 按 VA 精确 patch | LIEF（Rizin 用 `edit_hex` 按偏移） |
| 非 ELF 格式（PE / Mach-O / DEX） | LIEF `parse_any` |

## parse_any 支持的格式

```text
elf / pe / macho / dex / art / oat / vdex
```

这是本后端独有的能力 —— Rizin 只能处理 ELF。

## 加载状态判据

`meta_info(capabilities)` 的 `lief.status`：

| 值 | 含义 |
| --- | --- |
| `loaded` | 原生库正常，JNI 自检通过 |
| `failed: ... 空 stub ...` | 链接了 `lief_stub.cpp`，走 Kotlin fallback |
| `unavailable` | `System.loadLibrary` 失败 |

**CI 构建的包 `lief.status` 是 `loaded`，本地 debug 包常是 `failed`**
（submodule 为空 → CMake fallback stub）。

## Kotlin fallback

`lief.status != loaded` 时，`parse` / `list` 等只读操作会走 Kotlin 的
`ElfParser`，结果结构兼容但缺少 LIEF 独有的能力（`build` / `fix_sections` /
`add_export`）。这些写操作会明确返回不可用错误。

## fix_sections 的用途

被 strip 过或 section header 被破坏的 SO，Rizin 和大多数工具都无法正常
加载。`fix_sections` 用 LIEF 重建 section header 表：

```text
lief_api(action=fix_sections, workspaceId="ws_...")
  → 返回 {sizeBefore, sizeAfter, beforeSections, afterSections, ...}
```

## build 的冲突策略

```text
lief_api(action=build, outputName="patched.so", conflictStrategy="rename")
```

| 策略 | 行为 |
| --- | --- |
| `rename`（默认） | 自动加 `-1` / `-2` 后缀 |
| `overwrite` | 覆盖同名文件 |
| `skip` | 存在则跳过 |

## 输出落点

`resolveOutputFile` 把产物写到 **App 外部私有目录**：

```text
/storage/emulated/0/Android/data/com.soreverse.mcp/files/<name>
```

这个目录不需要任何存储权限，也不会污染用户的相册/文件管理器。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 loaded / stub / fallback 三态判据 |
