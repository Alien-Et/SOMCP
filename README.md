# SOMCP

> **原作者与致谢**
>
> 本项目源自 **[bilieebiliee1-design/SOMCP](https://github.com/bilieebiliee1-design/SOMCP)**，
> 原项目作者为 **SOMCP authors**（Copyright (C) 2026 SOMCP authors），以 AGPL-3.0-only 发布。
> 没有上游打下的 Rizin / LIEF / Unidbg / rz-ghidra 原生集成与 MCP 工具骨架，就没有这个分支。
> 本仓库是 **Alien-Et 的魔改分支**，在原作基础上做了大量裁剪与重构，改动清单见「与上游的差异」。
> 原项目的版权、许可与 NOTICE 声明完整保留，未做任何抹除。

本分支维护者：[Alien-Et](https://github.com/Alien-Et)　·　仓库：<https://github.com/Alien-Et/SOMCP>

---

## 这是什么

SOMCP 是一个**跑在 Android 手机上的本地 SO 逆向 MCP 服务器**。

它把手机变成一个可被任意 MCP 客户端（Claude Desktop、RikkaHub、Cherry Studio、自己写的脚本……）驱动的
ELF/APK 分析节点：通过 Streamable HTTP 暴露 81 个 MCP 工具，覆盖 ELF 结构解析、Rizin 反汇编与分析、
LIEF 重写与修复、补丁会话、构建导出、Flutter AOT 分析、JNI 动态注册还原、加固/混淆指纹识别等能力。

不依赖 Python，不需要电脑，不需要把样本传到云端——所有分析都在手机本地进程内完成。

| 项 | 值 |
| --- | --- |
| 包名 | `com.soreverse.mcp` |
| 版本 | `1.0.23`（`versionCode` 24） |
| 最低系统 | Android 8.0 / API 26 |
| 目标 SDK | 36 |
| 默认端口 | `8000` |
| 许可证 | AGPL-3.0-only |

---

## 核心特性

### 纯 MCP 服务器

本分支已移除原版的「分析」Tab、内置文件浏览器和前端分析面板，App 只做一件事：**把 MCP 服务跑稳**。
所有分析动作都从 MCP 客户端发起，App 本身不参与交互。

- 前台服务常驻，启动成功时 `START_STICKY`（启动失败回 `START_NOT_STICKY`，避免崩溃循环）。
- Ktor CIO HTTP 服务，默认监听 `0.0.0.0:8000`。
- **免 key**：`authEnabled` 出厂默认 `false`，局域网内客户端直接连，无需 Authorization。
  需要收紧时可在「设置 → 访问」里打开 Token。
- 外部拉起：`am start -n com.soreverse.mcp/.AutoStartActivity` 可在不点图标的情况下后台启动服务。

### Root 任意路径直读

这是本分支最重要的改动。原版依赖 SAF 让用户授权一个「工作目录」，只能在目录内工作；本分支改为
**有 root 就直接读任意路径**，不复制、不落盘、不产生副本。

原理：

```
MCP 调用 so_open(path="/data/app/~~xxx==/com.foo-yyy==/lib/arm64/libbar.so")
        ↓
RootShell.openStream(path)
        ↓  ProcessBuilder("su", "-c", "cat '<path>'")
        ↓  su 提权 → 以 root 身份 exec cat
        ↓  cat 的 stdout 直接接到 App 进程的 InputStream
        ↓
ZipInputStream / ELF parser 边流边解析（APK 走流式，SO 走随机访问读取）
        ↓
结果留在内存，字节用完即丢
```

- App 自身仍是普通 uid（如 `u0_a380`），打不开 `/data/app`、`/data/data/<other>`；
  真正读文件的是 `cat` 子进程（euid=0）。
- 全程管道，**没有任何临时文件**。
- 386MB 的 APK 实测可解析（`analyzer: builtin_apk_zip_root_stream`，14473 个条目），
  内存只占几百 KB 的 buffer。
- 失败兜底链：root 流 → 普通 `File.readBytes()` → 报错并说明 `root available=...`。

首页常驻 **Root 状态卡**：已授权显示绿色「Root 已授权」，未授权显示橙色「未获得 Root 权限」并给出
KernelSU / Magisk 授权指引，两种状态都可点「重新检测」（无需重启 App）。

### 无 root 也能用

没有 root 不等于不能用。非 root 下仍然可用：

| 能力 | 说明 |
| --- | --- |
| MCP 服务本体 | 完全正常 |
| `so_open` / `apk_analyze` 直连可读路径 | `/data/app/<pkg>/lib/<abi>/*.so`、`base.apk`（`644`/`755`，世界可读） |
| 全部 ELF 分析 | rizin / LIEF / capstone / ghidra 全在 APK 的 `lib/` 里 |
| `edit_*` / `build_so` | 输出到 App 自己的外部私有目录 |

非 root 的边界在于**找不到路径**而非读不到文件：`/data/app` 是 `0711`，不能 `ls`，但知道确切路径就能穿过。
`/data/data/<other>` 是 `0700`，非 root 物理上无解。

### 原生分析后端

| 后端 | 用途 |
| --- | --- |
| **Rizin** | 函数分析、CFG、xref、crypto 扫描、ESIL、字节搜索、反汇编、汇编、Ghidra 反编译 |
| **LIEF** | ELF 解析、section 内容读写、section header 重建、符号修改、地址 patch |
| **rz-ghidra** | `libcore_ghidra.so`，提供 `pdg` 伪代码 |
| **Capstone / Keystone / Unicorn** | Unidbg 模拟执行与指令编解码 |
| **Blutter** | Flutter 3.44.x / Dart 3.12.2 arm64 AOT 离线分析 |

Rizin 版本由 CI 自动取上游最新 release tag，不再 pin 固定版本。

### 特色分析工具

| 工具 | 作用 |
| --- | --- |
| `jni_api` | 扫描 `JNINativeMethod` 三指针表，把 Java 方法名/签名映射到真实原生地址 |
| `packer_api` | 加固/壳静态指纹：厂商标记、逐段 Shannon 熵、入口点异常、反分析字符串 |
| `obfusc_api` | 基于真实 basic block 图的 OLLVM dispatcher 识别与扁平化度量 |
| `strdecrypt_api` | 密文块定位 + 256 个单字节 XOR/add/sub/rol/ror 全量扫描 + 多字节 XOR / RC4 试解 |
| `antidebug_api` | 按 import / string / code_pattern 三级证据分级 |
| `import_api` | 经 `.gnu.version_r` 把导入符号精确归属到源库 |
| `flutter_blutter` | Flutter AOT 快照分析与 Dart 类/方法恢复 |
| `elf_*` 系列 | `elf_abi_profile` / `elf_byte_ledger` / `elf_section_anomaly` / `elf_phdr_invariants` / `elf_dynamic_tags` / `elf_dt_flags` / `elf_hash_tables` / `elf_reloc_census` 共 8 项 |
| `apk_*` 系列 | 签名校验、证书信息、ZIP 异常、ABI 覆盖 |

所有静态工具**不执行目标文件**，对不可信输入安全；结论带证据与置信度，不做过度断言。

---

## 快速开始

### 安装

从 [GitHub Releases](https://github.com/Alien-Et/SOMCP/releases) 下载对应 ABI 的 APK：

```text
SOMCP-<version>-arm64-v8a.apk     # 绝大多数手机装这个
SOMCP-<version>-universal.apk     # 不确定 ABI 时装这个
```

同目录的 `SHA256SUMS` 可用于校验完整性。

### 启动

打开 App → 首页点「启动」→ 服务在 `8000` 端口起来。

首页会显示：

- 服务状态（运行中 / 已停止）
- 监听地址与端口（本机回环 / 局域网两种 URL）
- Root 授权状态卡

### 连接

**电脑侧（推荐 adb forward）**：

```bash
adb forward tcp:8000 tcp:8000
# MCP 端点：http://127.0.0.1:8000/mcp
```

**同局域网直连**：

```text
http://<手机IP>:8000/mcp
```

**验证服务**：

```bash
curl -s -X POST http://127.0.0.1:8000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}'
```

### 典型工作流

```text
so_open(path="/data/app/~~xxx==/com.foo-yyy==/lib/arm64/libbar.so")
analyze_elf(view=stats)
analyze_functions
read_disasm(locator="so_function:libbar.so!JNI_OnLoad@0x1ecc")
jni_api(action=scan)
session_open
edit_asm(dryRun=true)
session_history(action=check)
build_so
```

APK 分析：

```text
apk_analyze(path="/data/app/~~xxx==/com.foo-yyy==/base.apk")
flutter_blutter(action=inspect, path="/data/app/~~xxx==/com.foo-yyy==/base.apk")
```

> `so_open(action=open_url)` 仍需要先选工作目录才能下载落盘。这是唯一保留 SAF 的地方，
> 因为下载文件总得有个存放位置；日常分析走 `path=` 直连，完全不需要它。

---

## MCP 工具体系

当前共 **81 个内置工具**（准确数量以 `tools/list` 返回的 `_meta.fullToolCount` 为准）。

默认 **lean 模式**只广告核心、底层网关和 meta 工具，降低 LLM 初始化上下文成本；
完整能力通过 `meta_info(action=describe)` 发现。

### 工具发现

```text
meta_info(action=help)
meta_info(action=tools)
meta_info(action=describe, tools=["edit_asm", "build_so"])
meta_info(action=workflows)
meta_info(action=suggest, workspaceId="...")
meta_info(action=errors)
meta_info(action=capabilities)
meta_info(action=health)
```

`meta_info(action=capabilities)` 是**真实能力面**的唯一来源，会明确列出 supported / partial / missing，
不会把未实现的底层 API 误报为已覆盖。

### 底层聚合网关

底层 API 通过 capability registry + 聚合网关暴露，避免工具数量爆炸：

```text
rizin_api(action=capabilities|command|analyze|functions|cfg|xrefs|search_bytes|crypto|esil|diff|asm|disasm|decompile)
lief_api(action=capabilities|dispatch|parse|parse_any|list|patch_address|add_export|remove_symbol|build|fix_sections|report)
unidbg_api(action=capabilities|dispatch|status|call|dump)
xanso_api(action=capabilities|dispatch|status|help|build-section|fix_sections)
flutter_blutter(action=inspect|analyze|status|result|cancel|packages|prune)
dynamic_api(action=capabilities|dispatch|status|roots|methods|analyze,
            op=unidbg_session_open|unidbg_session_call|frida_hook|frida_call|…)
jni_api(action=capabilities|scan|resolve|method_table)
packer_api(action=capabilities|scan|fingerprint)
obfusc_api(action=capabilities|scan|detect|deflatten|deflatten_capabilities)
strdecrypt_api(action=capabilities|scan|decrypt|hints)
antidebug_api(action=capabilities|scan|detect)
import_api(action=capabilities|trace|scan)
```

`dynamic_api` 是两层的：`action` 选动作，`op` 选具体后端操作。动态分析有 unidbg（模拟）和
frida（真机 hook）两个后端，共用同一套编排层；`dynamic_api(action=capabilities)` 的
`backends.dynamic` 会如实标注各后端是否可用。

### 搜索语法

```text
search_bytes(pattern="5F2403D5")          # Rizin 紧凑 hex
search_bytes(pattern="5F24..D5")          # nibble 通配符，. 表示任意半字节
search_bytes(pattern="5F2403D5:FFFF00FF") # bytes:mask
search_bytes(pattern="5F 24 ?? D5")       # MCP 兼容写法，自动规范化为 5F24..D5
search_strings(prefix="JNI.*Load", regex=true)
```

返回的 hit 包含 `addr` / `hexAddr` / `fileOffset` / `section` / `sectionOffset`，
可直接衔接 `read_hexdump` 或 patch 工具。

### 并发与限流

`so_open`、`search_bytes`、`analyze_*`、`read_disasm` 等重型工具受全局并发闸门保护。
自动化客户端应**串行调用**，遇到 `SERVER_BUSY` 按返回的 `retryAfterMillis` 重试同一请求。

---

## 设置

设置页为单页 M3 列表，分三组：

### 常用

| 项 | 内容 |
| --- | --- |
| 服务配置 | 端口、访问 URL、工具暴露策略 |
| 保活 | 唤醒锁、悬浮窗、开机自启 |
| 访问 | 绑定地址（`127.0.0.1` / `0.0.0.0`）、Token 开关 |
| 语言 | 中文 / English |

### 工具

| 项 | 内容 |
| --- | --- |
| 审计 | 自动快照、审计日志、最大快照数 |
| Blutter | Flutter 分析（内置 Runner 版本、兼容性规则） |
| 探测 | 环境探测 |
| 调用审计 | 工具调用统计 |

### 其他

| 项 | 内容 |
| --- | --- |
| 日志 | 运行日志环形缓冲 |
| 导出 | 构建产物列表与清理 |
| 备份恢复 | 设置导入导出 |

---

## 构建

### CI 构建（推荐）

推 tag 或手动触发 workflow 即可，`.github/workflows/build.yml` 会完成全套原生编译：

```text
Init submodules
Set up JDK 17 / Gradle 9.6.1 / Android SDK 36 / NDK 29.0.14206865 / CMake 3.22.1
Clone Rizin source (latest release tag)
Build native backends (Rizin + LIEF)
Build Unidbg native libs (capstone/keystone/unicorn)
Build rz-ghidra plugin (libcore_ghidra.so)
Restore release signing keystore
Build release APKs
Verify and ensure v2/v3 APK signing
Upload APK
```

产物：

```text
app-arm64-v8a-release.apk       # 约 55 MB
app-armeabi-v7a-release.apk
app-x86-release.apk
app-x86_64-release.apk
app-universal-release.apk       # 约 131 MB
```

**CI 包与本地 debug 包的关键差异**：CI 包含全套 native（`libkeystone.so`、`libunicorn.so`、
`libjnidispatch.so`、`libcapstone.so`、`libcore_ghidra.so`、`librz_native.so` + 27 个 `librz_*.so`），
Unidbg 模拟执行可用；本地 debug 包因 submodule 常为空，`emulate_*` 会报 `EMULATOR_UNAVAILABLE`。

### 本地构建

环境要求：

- JDK 17
- Android SDK 36
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- Gradle `9.6.1`

```bash
# Rizin 源码（非 git submodule，需手动 clone 一次）
git clone --depth 1 --branch <latest-tag> https://github.com/rizinorg/rizin.git third_party/rizin-src

# 原生依赖
bash build-native-android.sh

# 打包
./gradlew :app:assembleDebug
```

> **注意 Gradle 增量缓存**：修改 Kotlin 源码后若怀疑没有生效，用 `--rerun-tasks` 强制重编。
> 本项目遇到过 `assembleDebug` 报成功但 dex 里没有新代码的情况。

### Release 优化

- R8 代码压缩与混淆
- Android 资源压缩
- ABI 分包 + universal 包
- v1/v2/v3/v4 APK 签名
- 严格 native 链接：禁止 Rizin/LIEF/zstd 未解析符号混入 `.so`
- JNI keep 规则：保留 `RizinNativeEngine` / `LiefEngine` 的类名和 native 方法名

---

## 与上游的差异

本分支相对 `bilieebiliee1-design/SOMCP` 的主要改动：

### 移除

- **「分析」Tab** 及配套的 `AnalyzeTab` / `AnalyzeController` / `AnalyzeDataAdapter` / `ElfOverviewPanel`
- **工作目录 UI 与 SAF 授权流程**：不再有选目录入口。唯一残留是 `so_open(action=open_url)` 的下载落点
  （`treeUri` 兼容字段），日常 `path=` 直连完全用不到
- **Cloudflare Tunnel** 全链路：`CloudflareTunnelManager`、4 个 tunnel hook、runtimeInfo / CORS tunnel hosts、
  14 个 tunnel 设置项、`TunnelStatsSection`、CI 里的 Go / cloudflared 编译步骤
- **外观设置页**：`SettingsHub` 仍接收 `onTheme` / `onAccent` 等回调（`MainActivity` 侧仍在使用），
  但设置列表里已不再有外观入口
- 死代码：`DeepReportStore.kt`、`AiTypes.kt`、`ExternalLinks.kt`、`CrashReportActivity`、日志上报

### 重构

- **UI 单页化**：Compose + Material 3，两 Tab（控制台 / 设置），设置改为列表化三层导航
- **Root 优先的路径解析**：`EngineRuntimeSources` 的 `analyzeApk` / `openWorkspace` / `resolveLocalSoSource`
  全部改为 root 优先，废除 `copyViaRootToCache` 副本方案
- **流式 APK 解析**：`ApkAnalyzer.analyzeRootStream` / `analyzeFile`，大包不进堆
- **`RootShell`**：`listFiles` 改用 `find + stat`（原实现解析 `ls -la` 固定 9 列，设备实际格式不匹配导致全跳过）；
  `walkFiles` 改为单条 `find` + 批量 `stat`（`/data/app` 7725 个 so 用时 0.12s）
- **免 key**：`authEnabled` 默认 `false` + `lanNoAuthMigrated_v1` 迁移

### 修复

- `UnidbgEmulator` 的 ABI 判定：`endsWith("64")` → `contains("64")`
  （`arm64-v8a` 结尾是 `v8a` 不是 `64`，原逻辑恒 false，导致 arm64 设备误报 32 位）
- `apk_analyze` 外部路径 `EACCES`
- `so_open(action=list)` 在无工作目录时返回空列表
- CI CMake 4.x 兼容：`-DCMAKE_POLICY_VERSION_MINIMUM=3.5` + 幂等 sed 修 `cmake_policy(SET CMP0048 OLD)`
- CI keystore 恢复：`tr -d '[:space:]'` 去空白 + `keytool -list` 校验

---

## 许可证与再分发

本项目依据 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0-only）发布。
第三方依赖、submodule、生成资源和补丁仍分别遵循各自上游许可证。

> **再分发义务（重要）**
>
> 任何再分发（含修改、改名、二次打包版本）必须：
>
> 1. 保留版权与许可声明及 [NOTICE](NOTICE)；
> 2. 继续以 AGPL-3.0 授权；
> 3. 向每一位接收者提供完整对应源代码；
> 4. 标注所做修改。
>
> 闭源分发、抹除署名或改名冒充原创即构成侵权——依 AGPL-3.0 第 8 条授权自动终止。
> 本软件在 MCP `initialize` / 健康检查返回与「关于」页固定输出许可证、上游仓库与版权，
> 作为运行时溯源证据。

---

## 免责声明

- 本工具只适合分析**自己有权处理**的文件。第三方二进制的逆向、修改和分发可能受法律、协议或平台规则限制。
- 汇编补丁更适合等长覆盖或明确边界内 patch，不会自动搬移后续代码。
- 手机无法单独证明自己从公网可访问；公网可达性需要远端客户端或外部探测配合。
- 使用 root 能力读取其他应用数据前，请确认你拥有相应授权。

---

## 致谢

- **[bilieebiliee1-design/SOMCP](https://github.com/bilieebiliee1-design/SOMCP)** — 上游项目，本分支的一切基础
- [Rizin](https://github.com/rizinorg/rizin) — 逆向工程框架
- [rz-ghidra](https://github.com/rizinorg/rz-ghidra) — Ghidra 反编译器集成
- [LIEF](https://github.com/lief-project/LIEF) — ELF 解析与重写
- [Unidbg](https://github.com/zhkl0228/unidbg) — 模拟执行
- [Capstone](https://github.com/capstone-engine/capstone) / [Keystone](https://github.com/keystone-engine/keystone) — 反汇编与汇编
- [Blutter](https://github.com/worawit/blutter) — Flutter AOT 分析
