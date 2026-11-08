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
| 版本 | `1.0.33`（`versionCode` 34） |
| 最低系统 | Android 8.0 / API 26 |
| 目标 SDK | 36 |
| 默认端口 | `8000` |
| 许可证 | AGPL-3.0-only |

---

## 项目文档

完整的技术文档在 [`docs/`](docs/)，按项目结构分类编号（01–19）：

| 编号 | 内容 |
| --- | --- |
| [01](docs/01.项目总览与架构.md) | 项目总览与分层架构 |
| [02](docs/02.MCP协议层.md) | MCP 协议层（HTTP / 鉴权 / 并发闸门） |
| [03](docs/03.工具目录与分类.md) | 81 个工具的分类清单 |
| [04](docs/04.引擎运行时.md) | 引擎运行时门面 |
| [05](docs/05.Root通道与路径解析.md) | Root 通道与路径解析 |
| [06](docs/06.ELF解析与审计.md) | ELF 解析与 15 项审计 |
| [07](docs/07.Rizin后端.md) | Rizin 后端 |
| [08](docs/08.LIEF后端.md) | LIEF 后端 |
| [09](docs/09.编辑会话与补丁.md) | 编辑会话与补丁 |
| [10](docs/10.APK分析与签名审计.md) | APK 分析与签名审计 |
| [11](docs/11.Flutter与Blutter.md) | Flutter / Blutter |
| [12](docs/12.动态分析.md) | 动态分析（Unidbg / Frida） |
| [13](docs/13.特色分析工具.md) | 特色分析工具（JNI / 加固 / 混淆 / 解密） |
| [14](docs/14.原生层.md) | 原生层（C++ / JNI） |
| [15](docs/15.UI层.md) | UI 层 |
| [16](docs/16.设置项参考.md) | 设置项参考 |
| [17](docs/17.构建与CI.md) | 构建与 CI |
| [18](docs/18.错误码与排障.md) | 错误码、数据目录与排障 |
| [19](docs/19.变更总览与总结.md) | 变更总览与总结 |

> **文档与代码同步是硬性要求**：每次改动项目，对应编号的文档必须跟着改，
> 一轮改动结束后更新 `19.变更总览与总结.md`。详见 [`docs/README.md`](docs/README.md)
> 的维护规则。

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
**有 root 就直接读任意路径**，主路径不复制、不落盘。

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
- 读 APK 走纯管道，**不产生临时文件**；386MB 的包实测 21 秒解析完（14473 个条目），
  内存只占几百 KB 的 buffer。
- **能直读的优先直读**：`File.canRead()` 为真的文件走 `analyzeFile`（随机访问，
  解析完整）；只有 App 读不到的路径（`/data/data` 私有、权限 000/600）才走 root 流。
- **少数需要随机访问的工具会落一份临时副本**，落点在 App 私有缓存，用完即删：
  - `flutter_blutter` → `cache/flutter-inspect/`
  - 4 个 `apk_*` 审计工具对 root-only 路径 → `cache/apk-audit/`
  - 都不写 `/sdcard`，不碰被测 App 的目录。
- 失败兜底链：root 流 → 普通 `File.readBytes()` → 结构化错误（`APK_INVALID` /
  `APK_READ_FAILED` / `APK_TOO_LARGE_FOR_AUDIT`），不会再出现「假成功」。

**`apk_analyze` 的两条解析路径**：

| 路径 | parser 标识 | manifest / dex | sha256 |
| --- | --- | --- | --- |
| App 可直读 | `builtin_apk_zip_streaming` | 真实解析 | 全文件，稳定 |
| root-only | `builtin_apk_zip_root_stream` | 真实解析 | 全文件，稳定 |

两条路径都做 ZIP magic 校验：传 `.so` / 文本 / 目录 / 空文件会明确返回 `APK_INVALID`，
不会返回「0 个条目但 ok:true」的空结果。

首页常驻 **Root 状态卡**：已授权显示绿色「Root 已授权」，未授权显示橙色「未获得 Root 权限」并给出
KernelSU / Magisk 授权指引，两种状态都可点「重新检测」。root 探测每 30 秒一次（不是 1 秒），
避免无谓的 `su` 进程 fork。

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
flutter_blutter(action="inspect", path="/data/app/~~xxx==/com.foo-yyy==/base.apk")
apk_sig_verify(path="/data/app/~~xxx==/com.foo-yyy==/base.apk")
```

返回里的 `parser` 字段标明走了哪条路径：`builtin_apk_zip_streaming`（App 可直读）
或 `builtin_apk_zip_root_stream`（root 流）。两者都会返回真实的
`manifest` / `dexFiles`（含 DEX 头）/ `resourcesArsc` / 全文件 `sha256`。

> `so_open(action=open_url)` 在没有 SAF 工作目录时会把下载的 `.so` 落到
> App 外部私有目录 `files/downloads/`（不需要任何存储权限）。
> 日常分析走 `path=` 直连，完全不需要工作目录。

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

> **装的包对不对，用 sha256 / CRC 验，不要只看时间戳**。
>
> 本项目实测过两次「代码改了、装上却不生效」，根因是**装的是上一版编译产物**（Gradle 增量
> 编译只重编变动的类，输出目录里留着旧 dex；签名安装时挑错文件就会把旧代码装上去）。
> 编译+签名+安装三步之后，先确认安装包与产物一致再测：
>
> ```bash
> # 安装包与本地产物
> sha256sum $(pm path com.soreverse.mcp | head -1 | sed 's/package://')
> sha256sum somcp_fixed_signed.apk
> # 单个 dex 的 CRC（尺寸/CRC 对不上就说明 dex 不是这一份）
> unzip -v <安装包> 'classes3.dex' | awk '/classes3.dex/{print $1,$7}'
> unzip -v somcp_fixed_signed.apk 'classes3.dex' | awk '/classes3.dex/{print $1,$7}'
> ```
>
> 「App 里搜到新代码字符串」不能作为证据：`pm install` 的安装目录可能留着上一轮的
> 包，`pm path` 指向的也不一定是你刚装的那个（APK 更新时目录名会变）。

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

**APK 分析正确性**

- `apk_analyze` 的 `sha256` 曾经是错的且不稳定：root 流路径用 `DigestInputStream`
  包住 `ZipInputStream`，而后者读到 central directory 就停，摘要只覆盖了「前 N 字节」，
  且 N 随内部缓冲预读量漂移 —— 同一个 386MB 包两次调用会得到两个不同的 hash
  （实测在 `fd53cae8…` 与 `2bd19f75…` 之间摆动）。现在 ZIP 遍历结束后继续把剩余
  字节读完再收摘要，两条路径都返回真正的全文件 sha256。
- `apk_analyze` 对非 ZIP 输入曾经返回 `ok:true` + `entryCount:0`：root 分支在 magic
  校验之前就 `return` 了，而 `analyzeRootStream` 自己没做校验。现在三条入口
  （`analyze` / `analyzeFile` / `analyzeRootStream`）都先验 4 字节 magic，
  `.so` / 文本 / 目录 / 空文件一律返回 `APK_INVALID` 并带上实际 magic 值。
- `analyzeRootStream` 的 `manifest` / `resourcesArsc` / `parsedBytes` / `flutter`
  曾经是「声明后从未赋值」的常量，等于什么都没解析。现在会真正读取
  `AndroidManifest.xml` 和 `classes*.dex` 并解析出 DEX 头（methodIds / classDefs 等），
  Flutter 判定复用已收集的条目清单。
- `analyzeFile`（App 可直读路径）同样补上了 manifest / dex 真实解析与 magic 校验。
- `analyzeApk` 曾对同一个文件开两次 root 流（先 probe 再解析），386MB 包白读一遍。
  现在能直读的走 `analyzeFile`，不能直读的只开一次 root 流。

**Root 通道健壮性**

- `RootShell.readBytes` 的超时形同虚设：`timeout` 变量算出来后从未使用，
  `waitFor` 排在「读完 stdout」之后 —— `cat` 卡在 FUSE/NFS 上时 `input.read()`
  会永久阻塞。现在用看门狗定时器到点强杀子进程，让阻塞的 read 抛异常返回。
- `RootShell.readBytes` 不抽 stderr，`cat` 写满 64KB pipe 缓冲后会与 App 互等死锁。
  现在 stderr 由守护线程抽干。
- `RootShell.openStream` 不检查子进程退出码，`cat <目录>` / `cat <不存在文件>`
  也返回「非 null 但立即 EOF」的流，上层无法区分空文件与读取失败。现在读到 EOF
  时校验退出码，非 0 抛 `IOException` 并带上 stderr 内容。
- 支持 `expectedSize` 参数：`cat` 中断导致字节数偏少时会明确报「截断」。

**其它**

- `so_open(action=open_url)` 曾经强制要求 SAF 工作目录，而工作目录 UI 已删，
  等于永久不可用。现在没有工作目录时落到 App 外部私有目录 `files/downloads/`。
- `so_open` 的错误 `badValue` 曾被拼成 `"/data/local/tmp (root available=true)"`，
  现在只返回原始路径。
- `EngineRuntime.guarded` 曾把所有 `IOException` 吞成一句无信息的
  `"I/O operation failed while handling the request."`，现在带上原始消息。
- 4 个 `apk_*` 审计工具曾对任何 APK 一律 `readBytes()` 整包进堆（386MB 包占 386MB 堆）。
  现在读入前按真实堆余量检查，放不下返回 `APK_TOO_LARGE_FOR_AUDIT` 并建议改用
  流式 `apk_analyze`；root-only 路径先流式落临时文件再读，用完即删。
- `flutter_blutter` 曾对 root-only 路径报 `EACCES`（`isFile` 为 true 但 `open()` 被拒）。
  现在先流式落 App 私有缓存再解析，`inspect` 同时接受 `.zip`。
- 首页 Root 状态卡曾 1Hz 轮询，每次 `su -c id` 约 25ms —— 一天 86400 次 fork。
  现在降到 30 秒一次。
- `UnidbgEmulator.writeTemp` 的 `deleteOnExit()` 在 Android 上不可靠（进程被 kill
  时不执行），`cache/unidbg` 会堆残留 `.so`。现在写之前先清理 5 分钟前的旧文件。

**构建 / CI**

- `UnidbgEmulator` 的 ABI 判定：`endsWith("64")` → `contains("64")`
  （`arm64-v8a` 结尾是 `v8a` 不是 `64`，原逻辑恒 false，导致 arm64 设备误报 32 位）
- `apk_analyze` 外部路径 `EACCES`
- `so_open(action=list)` 在无工作目录时返回空列表
- CI CMake 4.x 兼容：`-DCMAKE_POLICY_VERSION_MINIMUM=3.5` + 幂等 sed 修
  任意 `cmake_policy(SET CMPxxxx OLD)`（含 keystone/LLVM 的 `CMP0051`）
  与任意 `< 3.5` 的 `cmake_minimum_required`（含 `2.8.7` 这类高位 minor）；
  递归整棵 `third_party/` 源码树，三个原生脚本共用同一套规范化
- `test-v11.yml` 补装 `cmake;3.22.1`（与 `build.yml` / `release.yml` 对齐，
  否则 Unidbg 步骤回落到 runner 的 CMake 4.4 并在 keystone 配置阶段挂掉）
- `setup-gradle` 的 504 重试（`services.gradle.org` 偶发不可用）
- 删除 `test.yml` + `test-v2..v10` 十个从未运行过的迭代残骸，只留 `test-v11.yml`
- 工具链版本收敛到每个工作流顶层的 `env:` 块（`SOMCP_JAVA_VERSION` /
  `SOMCP_GRADLE_VERSION` / `SOMCP_ANDROID_PLATFORM` / `SOMCP_BUILD_TOOLS` /
  `SOMCP_NDK` / `SOMCP_CMAKE`），`upload-artifact` 统一 `@v7`；
  `tools/check-workflow-versions.py` 守卫跨文件一致性并接入 CI
- CI keystore 恢复：`tr -d '[:space:]'` 去空白 + `keytool -list` 校验

**第二轮全项目扫描（2026-11-07）**

- `edit_asm` 的 NOP 填充会写出**半个指令**（arm64 给 `byteLength=6` 产 `1F 20 03 D5 1F 20`），
  而 `edit_check` / `build` 都不校验指令边界 —— 打包成功、目标执行到那段才 SIGILL。
  现在 `repeatBytes` 强制整宽度，`edit_asm` 提前返回 `SIZE_MISMATCH` + `badValue{alignment}`。
- `workspaces` 是**无上限**的 `ConcurrentHashMap`（同文件的 `searchCache` / `PageStore` 都有上限），
  连续 `so_open` 多个大 SO 最终 `OutOfMemoryError` 干掉服务进程。现在 `MAX_OPEN_WORKSPACES = 8` +
  LRU 淘汰（`temporary` 优先），取用统一走 `touchWorkspace()` 刷新时间戳。
- `AppLog` 的共享 `SimpleDateFormat` 在锁外 `format()`，并发写日志会串时间戳甚至抛异常，
  而它正好发生在「写日志」路径上，会把真正的错误盖掉。现在格式化与入队同锁。
- Android `org.json` 的 `optString` 对 `JSONObject.NULL` 返回**字符串 `"null"`**，
  导致 `optString(k).ifBlank { null } ?: JSONObject.NULL` 这类兜底永远不触发 ——
  `patch_report.asm`、Blutter registry、`frida_call` 参数等 10 余处都会漏出字面量 `"null"`。
  现在统一走 `core/JsonText.kt` 的 `optText` / `optNonBlankText`。
- `Storage.readSource` 曾经**先**无条件 `su -c cat`、**后**才试直读：`0644` 的 `.so`
  直读 23ms、`su -c cat` 139ms，还占串行的 SU 通道。现在先直读（核对长度），失败才回退 root。
- `byteDiffRanges` 逐区间 new `JSONObject` 且**无上限**，LIEF 整体重排时会打爆堆；
  现在默认 `maxRanges = 256` 并输出 `diffTruncated` / `diffBytes`。
- `temporary` 参数此前是纯 no-op（没有任何清理逻辑读它），schema 描述与实际不符；
  现在语义为「disposable，达 8 个上限时优先淘汰」。

**第三轮 Flutter 全链路实测（2026-11-07）**

- **`flutter_blutter(action=analyze)` 此前在任何设备、任何 APK 上都 100% 失败**，
  两层原因：① runner 服务声明为 `isolatedProcess="true"`，隔离进程里
  SharedPreferences 不可用，而 `Application.onCreate` 无条件构造 `SettingsStore`
  → 进程 0.85 秒即崩；② 跳过初始化后，隔离进程的 SELinux 域仍让 runner 在
  `blutter_run_fd` 入口 SIGSEGV。现在改为**普通 `:blutter` 独立进程**
  （崩溃仍不影响主进程），runner 能真正执行。
- **ABI split 安装**的 APK（`libapp/libflutter` 在 split 里而不在 `base.apk` 中）
  不再误报 `FLUTTER_LIBS_NOT_FOUND`，会自动回退到同目录的 `lib/<abi>/`，
  并处理安装目录用 cpuAbi 简称（`lib/arm64`）的命名差异。
- **`analyze_outline` 在 stripped 二进制上完全不可用**（只查 ELF 符号表，
  `symbols=0` → 任何 locator 都 `FUNCTION_NOT_FOUND`）。现在按
  ELF 符号 → Rizin 恢复函数解析，并回报 `resolvedBy`。
- `FLUTTER_VERSION_NOT_SUPPORTED` 现在带 **`missingRunner`** 规格
  （dartVersion / engineRevision / snapshotHash / abi / compressedPointers），
  可直接拿去构建；同时说明为什么 runner 不能运行时下载。
**第四轮：Blutter 引擎按需下载（local runner）**

- ⚠️ **纠正上一轮的结论**：当时写的「runner 必须随 APK 分发、无法运行时下载」是**错的**。
  Android 限制的是外部存储那类「其他方可能篡改」的位置，**应用自己的 `files/`
  目录可以 `dlopen`** —— `myfler/fler-dart` 就是这么做的（`files/engines/` 下
  32 个 `dartvm_<ver>.so`，`targetSdk=36` 正常工作，实测 388 MB）。
- SoMCP 现在支持两条 runner 来源：内置（APK 的 jniLibs，按库名加载）与
  **本地（`files/blutter-engines/`，按绝对路径加载）**。把
  `dartvm_<dartVersion>.so` 丢进该目录即被自动发现；`runners.local.json`
  可显式描述，且描述里的 sha256 与实际文件不符时**拒绝加载**。
- 新增 `flutter_blutter(action=engines)` 查看目录与已发现的 runner；
  `tools/blutter-matrix/export_engines.py` + `.github/workflows/blutter-engines.yml`
  把既有构建产物导出成按需下载包（`curated-versions.json` 已覆盖
  `3.11.5 / 3.12.2 / 3.13.0 / 3.13.1` + 4 个 beta，正好覆盖本机实测遇到的版本）。
- 体积账：一个 runner 约 12.6 MB；覆盖设备上全部 Flutter 应用只需 **2–3 个版本 ≈ 25–38 MB**。

**第八轮：81 个工具全量实测，修掉 6 个工具侧 bug（2026-11-08）**

用真实大样本（一个 150 MB 的 Flutter 应用，66 个 SO，含商用加固）逐个工具以真实参数调用并交叉验证，
发现并修复 6 个工具侧缺陷：

| 严重度 | 工具 | 问题 | 修法 |
| --- | --- | --- | --- |
| 高 | `cache_clear` | 无条件 `workspaces.clear()`，把**已解析的 ELF 与编辑会话**当缓存清掉 → 所有 `workspaceId`/`editSessionId` 静默失效（复现：`so_open` → `analyze_elf` 正常 → `cache_clear` → `analyze_elf` 报 `WORKSPACE_NOT_FOUND`）。文档还把这个错误归因成 LRU 淘汰 | 默认只清派生缓存；新增 `dropWorkspaces`（默认 `false`）显式要求才丢弃 |
| 高 | `read_disasm` / `rizin_api` | ARM64 反汇编的**立即数与地址被寄存器别名替换腐蚀**：Rizin 用朴素子串替换把 `x29`→`fp`、`x30`→`lr`，于是 `[sp, #0x30]` 变成 `[sp, 0lr]`（字节级复核：`FD 7B 03 A9` = `stp x29, x30, [sp, #0x30]`） | `cleanDisasmLines()` 里按同一映射反向修回（`0lr`→`0x30`、`0fp`→`0x29`） |
| 中 | `edit_symbol` | schema 把 `op=rename` 标为「shortcut, bypasses edits[]」，实现里只有 add/remove 两个分支 → 快捷方式不可用，报 `edits[] is required` | 补 `rename` 分支（`locator`=旧符号、`name`=新名字） |
| 中 | `analyze_outline` | 编造一个「覆盖整函数、`instructionCount: 0`」的假基本块，与 `analyze_cfg` 的真实结果（同函数 8 个块）直接矛盾 | 改为 `basicBlocks: []` + `basicBlocksAvailable: false` + 提示 |
| 中 | `android_ident_note` | `.note.android.ident` 结构读错：把 `offset 4` 当打包整数，于是 NDK 版本 `"r21b"` 被读成 `49.196.12914`，build number 前带 60 个 NUL | 按 AOSP 真实布局读两个定长字符串 → `ndkVersion: "r21b"` / `buildNumber: "6352462"` |
| 低 | `patch_apply_verify` | 自检详情自相矛盾：`status: preserved` 配 `detail: "reloc (offset,type) sets differ"` | detail 按实际分支生成 |

同轮记录 4 项待处理（未改）：`obfusc_api` 在非混淆库上大面积误报（非混淆库被判 33/40 函数「已扁平化」且 `confidence: high`，判中的全是 libc++ 模板自身的分支结构）、`analyze_functions` 在 Rizin JSON 解析失败时静默降级为 LIEF 符号、参数错误提示不点名缺失字段、多个工具响应体无上限（实测 `analysis_report` 1.7 MB / `workspace_export` 1.1 MB 内联返回，而 `toolResultMaxChars` 默认为 0）。

**第九轮：1.0.30–1.0.33 把上轮遗留全部清零（2026-11-08）**

| 版本 | 严重度 | 工具 | 问题 | 修法 |
| --- | --- | --- | --- | --- |
| 1.0.30 | 高 | `analyze_functions` | 函数数超过约 400 时永久降级 `lief-dynsym-fallback`（`calls` 全 0），而 `rizin_api(analyze)` 明确报 `functions: 1390` | `rizin_core.cpp` 的 `rzFunctions` 用固定 `char b[512]` 逐函数 `snprintf`，长名截断后整份 JSON 非法 → 改 `std::string` 动态拼接；`escJson` 补 UTF-8 校验（非法字节会毒死 `NewStringUTF`） |
| 1.0.31 | 中 | `analyze_outline` | `entry0` 真实起点 `0x41a60`，工具报 `0x268896` | `optString` 把 JSON number `268896` 变成字符串再按十六进制解析 → 优先 `optLong` 按数值读 |
| 1.0.31 | 中 | `search_bytes` | 传 `limit: 3` 返回 90 条 | schema/handler/native 三层都没有该参数 → 补全并在两条路径截断 + 回传 `totalHits`/`truncated` |
| 1.0.31 | 中 | `antidebug_api` | `pthread_cond_signal` 被判「信号处理器自检」、`dl_iterate_phdr` 被判「动态加载」 | 子串匹配改精确相等，规则表去掉裸 `"signal"` |
| 1.0.32 | 中 | `edit_hex`/`edit_asm`/`patch_length_guard` | 按 schema 传 `"0x4a0"` 报 `Missing byteOffset` | `optInt` 不解析字符串也不认 `0x` → 新增 `parseOffsetField()`，9 处读取全改 |
| 1.0.33 | 中 | `obfusc_api` | 非混淆库 `overall: "flattened"`、6 函数 `confidence: high` | fan-in 阈值相对化（≥ 平均入度 3 倍 **且** ≥ 总边数 25%）→ 复测全 `clean` |
| 1.0.33 | 中 | `edit_asm`/`read_disasm` | 参数错误不点名缺失字段 | 提前检出「locator 写进 edits[]」并说明怎么改；节区 locator 报 `INVALID_LOCATOR_KIND` + `acceptedForms` |
| 1.0.33 | 中 | `analysis_report` | 单次响应 1,728,075 字符 | 新增 `compact`（默认 true）/`sampleLimit`，保持数组形状只截条目 + `totals`/`truncation` → 35,042 字符（缩小 49 倍）；`wrapToolResult` 截断不再把 `ok:true` 翻成 `ok:false`；全局默认上限 0 → 600,000 |

同轮顺带修好 3 个失效单测（测试侧，源码无改动）：`SubAgentRolesTest` 是孤儿文件（被测源码已随 AI 工具移除），删除；`ToolCatalogLeanTest` 3 个断言仍指着 `agent_api`，改为断言通用 gateway 规则 + 显式钉住「已移除」；`StringDecryptorTest` 的「密文」夹具跨度只有 `0x2F`，不满足 `spansByteSpace` 的 `≥ 0x80`，换成真密文形态。另修 `ElfAuditTest` 的 note 夹具（12 字节 desc → AOSP 真实的 132 字节布局）。**`testDebugUnitTest` 172 项全绿**（修前因编译失败一项都跑不了）。

**第七轮：用某 Flutter 应用做真实样本回归，`str_literal_classes` 三类新误报清零（2026-11-08）**

样本换成真实大型 Flutter 应用**某 Flutter 应用**（`com.example.app`，150 MB APK、68 个 SO、某厂商加固 + 加固 SDK 加固）。
`apk_*` 四件套全绿，`antidebug_api` / `strdecrypt_api` 都有实质产出；同时 `str_literal_classes`
又暴露三类 `base64_like` 误报——都是同一类问题的不同形态：

| 形态 | 样例 | 为什么漏网 | 修法 |
| --- | --- | --- | --- |
| **Itanium C++ 修饰名** | `N<len><ns>6detect5FridaE`（`<ns>::detect::Frida`） | 大小写混合 + 含数字，旧判据三条全中 | 新增 `looksMangledCppName()`：`_Z` 前缀，或 `N…E` 的 `<十进制长度><标识符>` 分量序列 |
| **含数字/缩写的标识符** | `saveAppInfo2File`、`delayRestoreSIGILLHandler`、`getJavaStaticMethodID` | `CAMEL_RE` 要求「纯字母 + 每个大写后必跟小写」，带数字或缩写就过不了 | 新增 `looksIdentifierLike()`，改用统计特征（最长小写连续段 ≥ 3、大写占比 ≤ 45%、数字占比 ≤ 25%），用 15 个真实标识符 + 13 个真实 base64 校准 |
| **符号表条目 / 路径空段** | `.dynstr` 里全部 92 个符号名；`com/vendor/加固sdk/crash/` | 符号表按定义装的就是标识符，不该套 base64 判据；结尾 `/` 让 split 产生空段 | 新增 `isSymbolTableSection()`（`.dynstr`/`.strtab`/`.symtab` 不参与 base64/hex 分类）；路径分段忽略空段 |

实测：样本加固库2.so 的 `base64_like` **22 → 0**，样本加固库.so **9 → 0**。

**第六轮：MCP 传输层两处规范违规 —— 「服务端 200、客户端却说没连上」（2026-11-08）**

现象：SOMCP 进程活着、HTTP 200、81 个工具全部正常响应，但客户端的工具通道整体不可用，
报 `MCP client <uuid> is not connected`。用 `curl` 直接打 `127.0.0.1:8000/mcp` 拿到 200，
证明服务端没问题，再查两处规范细节，两个都违反：

- **`initialize` 不签发 `Mcp-Session-Id`**。规范要求服务端签发、客户端后续每个请求回传。
  本服务不保存会话状态，id 是身份令牌而非状态句柄 —— 但它仍必须被签发：拿不到 id 的客户端
  无法区分「我的会话还活着」和「我的会话已经没了」。新增 `Sessions`：签发 UUID（客户端已带
  则沿用）、每个请求刷新 `lastSeen`、TTL 1 小时 + 上限 64 条 LRU、未见过的 id 宽松采纳不 404。
- **`GET /mcp` 是「写完即 EOF 的假 SSE」**。旧实现用 `respondText(...)` 返回 SSE，产出的是
  **完整响应**（`Content-Length: 75`）并在写完立刻关闭连接；客户端发这个请求就是为了订阅
  服务端推送，拿到瞬间 EOF 会读作「连接掉了」。改成 `respondTextWriter(...)` 进入循环，
  每 10 秒 `: keep-alive` + `flush()`（间隔必须明显小于 CIO idle 超时 45s），并发上限 8 条。
- 补 `DELETE /mcp` → `204`；旧版 `GET /sse` 同样改成真流（`event: endpoint` 握手后保持长连接）。

| 项 | 修前 | 修后 |
| --- | --- | --- |
| `initialize` 响应头 | 无 `Mcp-Session-Id` | `Mcp-Session-Id: b01f02a3-…` |
| `GET /mcp` | `Content-Length: 75`，立即 EOF | `Transfer-Encoding: chunked`，每 10s `: keep-alive` |
| `DELETE /mcp` | 无此路由 | `204 No Content` |

> 排查这段时踩到一件容易误判的事：**对方 App 被系统杀掉**也会让客户端报「未连接」，
> 而那与 SOMCP 无关（实测 NP Manager 的端口回 `{"code":403,"message":"MCP server is not enabled"}`）。
> 两条判据（服务端 200 / `initialize` 是否签发 id / `GET /mcp` 是 chunked 还是 Content-Length）
> 已写进 `docs/18.错误码与排障.md`。
**第五轮：ELF / APK 审计工具全部失真，用真实样本逐个复现并修复（2026-11-08）**

本轮用 某开源测试应用（`com.example.tool`）的 `样本A.so` / `样本C.so` / `样本B.so`
和真实 `base.apk` 做样本，把 10 个此前「看起来在工作、实际结论是错的」的 bug 全部暴露并修掉。

- **`apk_sig_verify` / `apk_cert_info` / `apk_abi_coverage` / `apk_zip_anomaly` 四件套此前全是假的**，
  根因是三处字节解析错误：
  - `readEntry` 用 `Inflater()` 解 ZIP 条目：ZIP 的 deflate 是 **raw deflate**（无 zlib 头），
    默认 `nowrap=false` 会去找 2 字节 zlib 头并抛 `incorrect header check`，被 catch 成 `null`
    ⇒ **所有压缩条目都读不出来**。后果是 `abiCoverage` 的 `parsedBytes` 恒 0、`files[]` 里没有
    `bits/architecture/soname`；`cert_info` 读不到 `META-INF/*.RSA` 兜底证书；ZIP 对齐检查全部失效。
    改为 `Inflater(true)`。
  - `abiCoverage` 的默认预算 `2 * 1024 * 1024 * 1024` 声明为 `Int`，溢出成 `-2147483648`，
    `parsedTotal + e.usize <= maxParsedBytes` 恒 false ⇒ 一个 SO 都不解析。签名改 `Long`，
    并把「首字节 0x7F」的弱判据换成完整 ELF magic（`7F 45 4C 46`），非 ELF 条目明确标 `not_elf`。
  - `signerBlocks` 漏读 v2/v3 block value 最外层的 sequence 长度前缀：真实布局是
    `uint32 signersSeqLen | (uint32 signerLen + signer)*`，旧实现从偏移 0 读长度把整段当一个 signer，
    切出来的 signer 起点整体错 4 字节 ⇒ `parseSigner` 切完 signedData 就用尽缓冲区直接 `return null`。
    这是 `certCount:0` 和 `signers:[]` 的唯一原因。
  - digest / signature 记录漏读 `innerLen` 字段：两条记录都是 `len | algId | innerLen | data` 四段式，
    旧实现从 `+8` 切到 `+4+len`，把 `innerLen` 拼进了数据前面 ⇒ digest 的 `expected` 变成
    `20000000c3e6fd76…`（与 computed 永不相等，每个包都判 `digest_mismatch`）、RSA 签名字节多 4 字节
    前缀（验签必然 `invalid`）。同轮把 `DIGEST_CHUNKED_SHA256/SHA512` 从 `0x0301/0x0302` 订正为
    规范值 **`0x0103/0x0104`**（旧值让 digest 从不参与比对、还把 0x0103 误当 DSA 算法）。
    签名覆盖范围经 openssl 双向实测确认为**裸 signed data**（不加长度前缀）。
  - SONAME 冲突检测未按 ABI 分组：同一个库在 arm64-v8a / armeabi-v7a / x86 / x86_64 下大小天然不同，
    旧实现把所有 ABI 的 size 混进一个集合 ⇒ 任何多 ABI 的 APK 都误报
    `soname X maps to multiple sizes`。改为按 `abi/soname` 分组。
  - 实测对照（样本 `base.apk`，2.5 MB）：修前 `verdict=parsed_unverified` / `certCount=0` /
    `parsedBytes=0` / `findings=[2 条误报]`；修后 `verdict=verified`（证书 `CN=<vendor>`，
    SHA-256 `268b5590…` 与 `apksigner` 一致）、`certCount=1`、`parsedBytes=529768`、`findings=[]`、
    12 个 SO 全部解出 bits/architecture/soname。
- **`elf_hash_tables` 在有 `DT_GNU_HASH` 的 SO 上报 `hasGnu:false`**：`bytesForVaddr` 要求
  「请求的整个窗口完整落在文件内」才返回数据，而调用方传的是**上界**（DT_GNU_HASH 512 KiB、
  DT_HASH 64 KiB），真实哈希表往往只有几十字节 ⇒ 存在的表被判定为不存在。改为按上界处理
  （截到文件尾、至少 4 字节可读）。实测 `样本A.so` 修前 `hasGnu:false`，修后
  `nbuckets:1 / symoffset:63 / bloomSize:1 / emptyBuckets:1`。
- **`code_padding_caves` 把结构性节当成可复用空洞**：旧实现只跳过 `SHT_NULL/SHT_NOBITS`，于是把
  `.dynsym` 的 62 段 19 字节零值、`.dynamic` 的结束标记、`.got`/`.got.plt` 未用槽、`.rela.*` 的
  addend、`.eh_frame`、`.gnu.version_r`、`.note.android.ident`、`.data` 全报成「可 patch 空洞」——
  在 `样本A.so` 上 69 个候选里 60+ 是 `.dynsym` 假空洞，写进去会破坏符号解析与重定位。
  新增 `NON_CAVE_SECTION_TYPES` / `NON_CAVE_SECTION_NAMES` 与 `isStructuralSection()`，按
  ELF 节类型 + 节名前缀双重过滤。实测：`样本A.so` / `样本B.so` 的 `caveCount` 归 0
  （确实没有 ≥16 字节填充），`样本C.so` 只报 7 个真空洞（`.rodata` × 5、`.text` × 2），
  `patch_cave_finder` 给出 2 个 `safeForPatch=true` 候选。
- **`import_api` 把所有导入符号都归到第一个 `DT_NEEDED` 库**：`DT_VERNEED` 的常量写成了
  `0x6FFFFFFF`，而那是 **`DT_VERNEEDNUM`**，正确值是 `0x6FFFFFFE` ⇒ `parseVerneed` 恒空 ⇒
  `mode` 恒为 `needed_heuristic`。另加 `SymbolInfo.tableIndex` 字段（`ElfModels.kt` / `ElfParser.kt`），
  用 dynsym 的**原始下标**查 versym —— 旧代码用过滤后的列表下标，符号一被筛掉就整体错位。
  实测：`样本C` / `样本A` 的 `mode` 都是 `gnu_version_r`，`libc.so` n=33/59、`liblog.so` n=3/1
  分别正确归属。
- **`analyze_string_xrefs` 的 locator 坐标系猜错**：调用方传的是 vaddr，而 Rizin 的 `i` 命令是
  文件偏移。`translate_address` 补上（歧义时报错并给两个候选），`engine_find_function_at` 也支持
  vaddr 自动识别。实测 `'so_string:样本C.so!a24'` / `'a24'` / `'0xa24'` / `'0x1100'` 全部 ok。
- **4 个工具此前直接 HTTP 500（裸异常逃出 dispatch）**：`workspace_export` / `edit_hex` /
  `edit_asm` / `edit_symbol`。修法：`McpHttpServer` dispatch 层加 `try/catch`（`CancellationException`
  透传）统一返回 `TOOL_EXECUTION_FAILED` + `hint`；`ToolCatalog` 4 处 `a.getJSONArray("edits")` 改
  `optJSONArray` 并在缺失时返回 `INVALID_ARGUMENT`；`workspace_export` 补 `workspaceId` 非空校验
  （`WORKSPACE_REQUIRED`）。
- **`str_literal_classes` 的 `base64_like` 把 Java 标识符判成 base64**：`nativeInitCipher`(16) /
  `nativeConstructor`(17) 刚好越过 `BASE64_RE` 的 16 字符门槛且「小写+大写」被算成「大小写混合」；
  `com/example/tool/manager/adb/PairingContext`(38) 含 `/` 且大小写混合。新增 `CAMEL_RE`（纯字母无数字的
  驼峰串一律排除）与 `isIdentifierSegment()`（含 `/` 且每段都是合法 Java 标识符形态的判为包路径）。
  同时补源码扩展名黑名单，`hkdf.cc.inc` / `digest.cc.inc` / `e_aes.cc.inc` 不再被标 `package_name`。
  实测 `base64_like` 从 3 个降到 **0**。
- **`strdecrypt_api` 的候选里混入半字节查表（40 → 2）**：第一轮的 `SCAN_MIN_BYTES=10` /
  `SCAN_MIN_ENTROPY=3.5` 挡不住 `0x01 02 03 00 05 06 07 04 ...` 这类 AES/GF(2^8) nibble 表——
  它取值只有 `0x00..0x0F` 十六个，4 bit 均匀分布，**熵恰好顶到 4.0**，比真密文门槛还高。
  新增 `spansByteSpace()`，要求 `maxByte >= 0x80` 且 `max - min >= 0x80`。实测 `blobCount`
  **40 → 2**，剩下的 13 字节 / 17 字节候选 `maxByte` 分别是 `0xFF` / `0xFD`、可打印率 0。
- **回归**：81 个工具空参数探测 = `✅ 8 正常 / ⚠️ 72 业务错误（服务活着）/ ❌ 0 传输异常`；
  真实样本 31 项功能断言 **31/31 通过**；`edit_hex` → `session_history check` →
  `patch_apply_verify`（`verdict: pass`，`changedBytes: 4`）→ `build_so`（sha256 与
  `newTargetVersion` 一致）编辑闭环实测通过。
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
