# 03 · 工具目录与分类

对应源码：`mcp/ToolCatalog.kt`（3,152 行）、`ToolCatalogRegistry.kt`、
`ToolCatalogPresentation.kt`、`ToolContracts.kt`、`ToolHandler.kt`

## 工具总数

**81 个**。准确数量以 `initialize` 返回的 `_meta.fullToolCount` 为准，
不要在文档里写死成常量 —— 加工具时这里和 `README.md` 都要改。

## ToolClass 三档

```kotlin
enum class ToolClass { CORE, META, EXTRA }
```

| 档位 | 数量 | lean 模式是否广告 |
| --- | --- | --- |
| `CORE` | 28 | 是 |
| `EXTRA` | 50 | 否（通过 `meta_info(describe)` 发现） |
| `META` | 3 | 是 |

`lowlevel` 分类的 11 个网关虽是 `EXTRA`，但 `ToolCatalogRegistry.leanNames()`
会把它们强制加入广告列表 —— 它们是**入口**，藏起来等于让对应能力不可达。

## 按 category 分类

| category | 数量 | 说明 |
| --- | --- | --- |
| `analyze` | 33 | ELF/APK 分析、审计、指纹 |
| `workspace` | 10 | 工作区、源解析、APK 审计入口 |
| `lowlevel` | 11 | 底层聚合网关 |
| `edit` | 7 | 编辑与补丁 |
| `search` | 2 | 字节/字符串搜索 |
| `read` | 2 | 反汇编、hexdump |
| `session` | 3 | 编辑会话 |
| `emulate` | 5 | Unidbg 模拟 |
| `system` | 3 | 系统控制、日志、应用配置 |
| `build` | 1 | 构建导出 |
| `diff` | 1 | SO 对比 |
| `meta` | 2 | 元信息、证据清单 |

## 完整工具清单

### workspace（10）

| 工具 | 作用 |
| --- | --- |
| `so_open` | SO 分析入口。`action=open` / `list` / `open_url` |
| `so_close` | 关闭工作区 |
| `workspace_overview` | 工作区概要 |
| `workspace_export` | 导出当前（含编辑会话）SO 字节 base64 |
| `cache_clear` | 清理引擎缓存 |
| `apk_analyze` | 独立解析本地 APK |
| `apk_sig_verify` | v2/v3 签名解析 + digest 重算 |
| `apk_cert_info` | 证书信息 |
| `apk_zip_anomaly` | ZIP 结构异常 |
| `apk_abi_coverage` | ABI 覆盖情况 |

### analyze（33）

ELF 结构与审计：

`analyze_elf`、`read_stats`、`analysis_report`、`analyze_functions`、
`analyze_cfg`、`analyze_crypto`、`analyze_xrefs`、`analyze_esil`、
`analyze_outline`、`analyze_string_xrefs`

ELF 深度审计（15 个）：

`elf_abi_profile`、`elf_byte_ledger`、`elf_section_anomaly`、
`elf_phdr_invariants`、`elf_dynamic_tags`、`elf_dt_flags`、`elf_hash_tables`、
`elf_reloc_census`、`dt_needed_resolve`、`symbol_visibility_audit`、
`code_padding_caves`、`jni_mangle_codec`、`android_ident_note`、
`ndk_toolchain_fingerprint`、`libcxx_variant`

字符串与常量：

`str_encoding_census`、`str_literal_classes`、`crypto_const_fingerprint`、
`crypto_hash_constants`

端点与环境：

`net_endpoint_extract`、`file_path_roots`、`env_property_keys`

Flutter：

`flutter_blutter`

### read / search（4）

| 工具 | 作用 |
| --- | --- |
| `read_disasm` | 反汇编（含 Ghidra 伪代码） |
| `read_hexdump` | 十六进制 dump |
| `search_bytes` | Rizin 原生 byte pattern |
| `search_strings` | 字符串搜索（支持 regex） |

### edit（7）

| 工具 | 作用 |
| --- | --- |
| `edit_hex` | 按字节补丁 |
| `edit_asm` | 按汇编补丁 |
| `edit_symbol` | 符号修改 |
| `edit_fix_sections` | section header 重建 |
| `patch_cave_finder` | 找 code cave |
| `patch_length_guard` | 补丁长度守卫 |
| `patch_apply_verify` | 补丁应用后校验 |

### session（3）

| 工具 | 作用 |
| --- | --- |
| `session_open` | 开编辑会话 |
| `session_history` | snapshot / rollback / undo / redo / check |
| `session_audit` | 审计日志 |

### emulate（5）

| 工具 | 作用 |
| --- | --- |
| `emulate_call` | 调用导出函数 |
| `emulate_dump` | dump 内存 |
| `unidbg_session` | 会话管理（open/call/dump/registers/trace/close） |
| `unidbg_memory` | 内存 map/read/write/protect/unmap/maps |
| `unidbg_debug` | trace / breakpoint / 单步 |
| `unidbg_batch` | 串行批量流水线 |

### lowlevel 网关（11）

| 工具 | action 域 |
| --- | --- |
| `rizin_api` | `capabilities` / `command` / `analyze` / `functions` / `cfg` / `xrefs` / `search_bytes` / `crypto` / `esil` / `diff` / `asm` / `disasm` / `decompile` |
| `lief_api` | `capabilities` / `dispatch` / `parse` / `parse_any` / `list` / `patch_address` / `add_export` / `remove_symbol` / `build` / `fix_sections` / `report` |
| `unidbg_api` | `capabilities` / `dispatch` / `status` / `call` / `dump` |
| `xanso_api` | `capabilities` / `dispatch` / `status` / `help` / `build-section` / `fix_sections` |
| `dynamic_api` | `action` + `op`（unidbg / frida 两套后端操作） |
| `jni_api` | `capabilities` / `scan` / `resolve` / `method_table` |
| `packer_api` | `capabilities` / `scan` / `fingerprint` |
| `obfusc_api` | `capabilities` / `scan` / `detect` / `deflatten` / `deflatten_capabilities` |
| `strdecrypt_api` | `capabilities` / `scan` / `decrypt` / `hints` |
| `antidebug_api` | `capabilities` / `scan` / `detect` |
| `import_api` | `capabilities` / `trace` / `scan` |

### system / meta / build / diff（7）

| 工具 | 作用 |
| --- | --- |
| `system_control` | 服务状态、APK 桥接探测 |
| `system_logs` | 500 行环形日志缓冲 |
| `app_config` | App 设置读写 |
| `meta_info` | help / tools / describe / stats / batch / continue / health / capabilities / workflows / suggest / errors / report |
| `evidence_manifest` | 证据清单 |
| `build_so` | 构建导出（含 patch report、多输出变体） |
| `diff_so` | 两个 SO 的 ELF 层面对比 |

## 工具定义结构

```kotlin
private val soOpen = EngineToolHandler(
    ToolMeta(
        "so_open",                    // 名字（唯一）
        "【SO 分析入口】...",          // 中文描述
        "【PRIMARY SO ENTRY POINT】...", // 英文描述
        "workspace",                  // category
        ToolClass.CORE,               // 档位
        heavy = true                  // 是否参与并发闸门
    ) {
        objectSchema(props {
            "action".oneOf("open (default) | list | open_url", "open", "list", "open_url")
            "path" str "Absolute path or content:// URI (action=open)"
            // ...
        })
    }
) { engine, args, _ ->
    engine.open(args.str("path").ifBlank { args.str("filePath") }, args.bool("temporary", false))
}
```

`ToolMeta` 的 6 个位置参数顺序固定，加工具时注意别写错位。

## 加一个工具的正确步骤

1. 在 `ToolCatalog.kt` 里定义 `private val xxx = EngineToolHandler(ToolMeta(...)) { engine, args, _ -> ... }`
2. 加到文件末尾的 `val ALL: List<ToolHandler> = listOf(...)`
3. 在 `engine/` 下实现对应的 `EngineRuntime.xxx()` 扩展函数，用 `guarded { }` 包裹
4. 更新 `docs/03-tool-catalog.md` 的分类清单与总数
5. 更新 `README.md` 的工具数与相关章节

`ToolCatalogRegistry` 会在 `init` 时校验名字唯一性，重名会抛异常。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；按 category 完整列出 81 个工具 |
