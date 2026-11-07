# SOMCP 项目文档

本目录按**项目结构**分类编写，编号即阅读顺序。每篇文档对应源码里的一组真实模块，
不是泛泛而谈的概念介绍。

> **维护规则（重要）**
>
> 项目每变动一次，本目录必须同步更新。具体约定：
>
> - 改功能 → 更新对应编号的文档，并在该文档末尾的「变更记录」追加一行
> - 加/删 MCP 工具 → 更新 `03-tool-catalog.md` 的分类清单与总数
> - 改设置项 → 更新 `16-settings.md`
> - 改错误码 → 更新 `18-errors-and-dirs.md`
> - 改构建流程 / 原生依赖 → 更新 `17-build-ci.md`
> - 任何一轮改动结束后 → 更新 `19-changelog.md`（总览与总结）
> - 文档与代码冲突时，**以代码为准**，并立即修文档

## 文档索引

| 编号 | 文档 | 对应源码 |
| --- | --- | --- |
| 01 | [项目总览与架构](01-overview.md) | 全局 |
| 02 | [MCP 协议层](02-mcp-protocol.md) | `mcp/McpHttpServer.kt` |
| 03 | [工具目录与分类](03-tool-catalog.md) | `mcp/ToolCatalog.kt`、`ToolCatalogRegistry.kt` |
| 04 | [引擎运行时](04-engine-runtime.md) | `engine/EngineRuntime.kt`、`NativeSoEngine.kt` |
| 05 | [Root 通道与路径解析](05-root-and-paths.md) | `core/RootShell.kt`、`engine/EngineRuntimeSources.kt`、`engine/Storage.kt` |
| 06 | [ELF 解析与审计](06-elf-core.md) | `engine/ElfParser.kt`、`ElfAudit.kt`、`ElfOverviewBuilder.kt` |
| 07 | [Rizin 后端](07-rizin.md) | `engine/EngineRuntimeBackendRizin.kt`、`cpp/rizin_core.cpp` |
| 08 | [LIEF 后端](08-lief.md) | `engine/LiefEngine.kt`、`EngineRuntimeBackendLief.kt`、`cpp/lief_elf.cpp` |
| 09 | [编辑会话与补丁](09-edit-session.md) | `engine/EngineRuntimeEditSession.kt`、`EngineRuntimeEditPatch.kt`、`EngineRuntimeBuild.kt` |
| 10 | [APK 分析](10-apk-analysis.md) | `engine/ApkAnalyzer.kt`、`ApkSigTools.kt`、`EngineRuntimeApkAudit.kt` |
| 11 | [Flutter / Blutter](11-flutter-blutter.md) | `engine/FlutterArtifactInspector.kt`、`BlutterCoordinator.kt`、`blutter/` |
| 12 | [动态分析（Unidbg / Frida）](12-dynamic-analysis.md) | `engine/UnidbgEmulator.kt`、`FridaBridge.kt` |
| 13 | [特色分析工具](13-signature-tools.md) | `JniRegisterEngine.kt`、`PackerFingerprint.kt`、`ObfuscDetector.kt`、`StringDecryptor.kt`、`AntiDebugScanner.kt`、`ImportAttribution.kt` |
| 14 | [原生层（C++ / JNI）](14-native-layer.md) | `app/src/main/cpp/`、`nativecore/` |
| 15 | [UI 层](15-ui.md) | `MainActivity.kt`、`ServiceTab.kt`、`SettingsHub.kt` 等 |
| 16 | [设置项参考](16-settings.md) | `core/SettingsStore.kt` |
| 17 | [构建与 CI](17-build-ci.md) | `build.gradle.kts`、`.github/workflows/` |
| 18 | [错误码、数据目录与排障](18-errors-and-dirs.md) | 全局 |
| 19 | [变更总览与总结](19-changelog.md) | 全局 |

## 阅读建议

- **只想用起来**：`01` → `README.md`（仓库根）→ `18`
- **要改代码**：`01` → `02` → `03` → `04` → 目标模块对应编号
- **要排查问题**：`18` → 相关模块编号
- **要重新构建**：`17`
