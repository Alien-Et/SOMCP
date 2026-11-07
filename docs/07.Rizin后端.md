# 07 · Rizin 后端

对应源码：`engine/EngineRuntimeBackendRizin.kt`（1,266 行）、
`app/src/main/cpp/rizin_core.cpp`（1,648 行）、
`nativecore/NativeEngine.kt`（`RizinNativeEngine` 部分）

## 能力

| action | 作用 |
| --- | --- |
| `capabilities` | 能力面（supported / partial / missing） |
| `command` | 受控 raw command 通道 |
| `analyze` | 加载 SO + 自动分析，返回统计 |
| `functions` | 函数列表 |
| `cfg` | 基本块与边 |
| `xrefs` | 交叉引用（to / from / both） |
| `search_bytes` | Rizin 原生 byte pattern |
| `crypto` | 高熵区域 / 加密常量扫描 |
| `esil` | ESIL 单步 |
| `diff` | 两个 SO 的差异 |
| `asm` | 汇编 |
| `disasm` | 反汇编 |
| `decompile` | Ghidra 伪代码（需 `libcore_ghidra.so`） |

## 14 个 JNI 导出

`rizin_core.cpp` 导出到 `com.soreverse.mcp.nativecore.RizinNativeEngine`：

```text
rzSelfTest        rzAnalyze         rzFunctions       rzCfg
rzXrefs           rzDisassemble     rzAssemble        rzCommand
rzSearchBytes     rzScanCrypto      rzEsilStep        rzDiff
rzConfigureGhidra rzDecompile
```

`rzSelfTest` 是关键：它在 `System.loadLibrary` 之后立刻跑一遍自检
（设置 `asm.arch = arm` + 解码一条 ARM 指令）。失败时
`meta_info(capabilities)` 的 `rizin.status` 会标成
`failed: ...`，而不是假装可用。

## 构建产物与常见坑

`librz_native.so` 是通过 `--whole-archive` 把 27 个 `librz_*.so`
静态链接进去的，体积巨大：

| 构建类型 | `librz_native.so` 大小 |
| --- | --- |
| 完整构建（CI） | 64,344,368 字节（61 MB） |
| 空 stub（submodule 缺失） | 2.9 MB |

**判断 APK 里是不是真 Rizin**：查 `librz_native.so` 大小。2.9MB 就是
stub，运行时 `RIZIN_UNAVAILABLE`。

### Gradle CMake 缓存坑

改完 `third_party/rizin-src` 后重编，如果只删 `app/build/intermediates/cxx`
是**无效的** —— CMake configure 指纹没变，会直接链接旧的 stub 目标。

必须删 `app/.cxx` 才能彻底重建：

```bash
rm -rf app/.cxx app/build/intermediates/cxx
./gradlew :app:assembleDebug --rerun-tasks
```

### rizin-build 产物不在 git 里

`rizin-build/`（5.5 GB）和 `third_party/rizin-src/` 都在 `.gitignore` 里。
换机器或清缓存后必须重新跑：

```bash
bash build-native-android.sh
```

否则 CMake 检测不到 `rizin-build/<abi>/librz/util/librz_util.a`，
会 fallback 到 `rizin_stub.cpp`，产出 2.9MB 的空 stub。

### 版本由 CI 自动取最新

10 个 workflow（`build.yml` / `release.yml` / `test-v5..v11`）在 clone 前用：

```bash
git ls-remote --tags --sort=-v:refname https://github.com/rizinorg/rizin.git \
  | grep -oE 'refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$' | head -1
```

自动取最新的 `vX.Y.Z` release tag，不再 pin 死版本。

## search_bytes 的四种写法

```text
5F2403D5          # Rizin 紧凑 hex
5F24..D5          # nibble 通配符，. 表示任意半字节
5F2403D5:FFFF00FF # bytes:mask
5F 24 ?? D5       # MCP 兼容写法，会规范化为 5F24..D5
```

返回的 hit 包含：

```json
{
  "addr": "0x1234",
  "hexAddr": "0x1234",
  "fileOffset": 4660,
  "section": ".text",
  "sectionOffset": 1024
}
```

可直接衔接 `read_hexdump` 或 patch 工具。

## rz-ghidra 反编译

`decompile` 需要 `libcore_ghidra.so`（CI 构建时会编译 `rz-ghidra` 插件）。
缺失时返回明确的不可用错误，不会静默返回空。

## rzCommand 的安全边界

`rizin_api(action=command)` 是受控的 raw command 通道：

- 只读命令直接执行
- **写入、文件、shell、调试器命令需要 `unsafe=true`**
- `unsafe=true` 还要求 MCP 已启用 token 认证（即 `authEnabled=true`）

这样避免了「免 key + 任意 shell」的组合风险。

## 性能特征

| 操作 | 备注 |
| --- | --- |
| `analyze` | 大库（如 `libapp.so` 100MB+）耗时显著，受 `heavyGate` 串行化 |
| `functions` | 依赖 `analyze` 已完成 |
| `disasm` | 支持分页，`disasmLimit` / `disasmMaxBytes` 控制 |
| `decompile` | 最慢，Ghidra 反编译单函数通常 1-5 秒 |

Dart AOT 大库（`libapp.so`）上 Rizin 几乎识别不出函数 —— 这是 Rizin
对 Dart 快照的固有限制，不是 bug。函数导航应该用 `flutter_blutter`。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 CMake 缓存坑与 stub 体积判据 |
