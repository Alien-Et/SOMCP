# 12 · 动态分析（Unidbg / Frida）

对应源码：`engine/UnidbgEmulator.kt`（2,229 行）、`FridaBridge.kt`（1,223 行）、
`EngineRuntimeBackendUnidbg.kt`（1,433 行）、`EngineRuntimeDynamic.kt`、
`UnidbgBatchRunner.kt`

## 两个后端

| 后端 | 性质 | 依赖 |
| --- | --- | --- |
| **unidbg** | 模拟执行（不需要真机） | `libcapstone.so` / `libkeystone.so` / `libunicorn.so` / `libjnidispatch.so` |
| **frida** | 真机 hook（需要 root + frida-server） | `frida-server` / `frida-gadget` |

## 工具入口

```text
emulate_call / emulate_dump                  # 简化入口
unidbg_session / unidbg_memory / unidbg_debug / unidbg_batch   # 细粒度
dynamic_api(action=..., op=...)              # 统一网关
```

### dynamic_api 的 action

```text
capabilities | dispatch | status | roots | methods | analyze
```

### dynamic_api 的 op（细粒度）

unidbg：

```text
unidbg_session_open / unidbg_session_call / unidbg_session_dump
unidbg_session_registers / unidbg_session_trace / unidbg_session_close
```

frida：

```text
frida_status / frida_sessions / frida_open / frida_close
frida_hook / frida_call / frida_read / frida_write / frida_replace
frida_modules / frida_script / frida_backtrace
```

## Unidbg 的 native 库

四个库缺一不可：

| 库 | 作用 |
| --- | --- |
| `libcapstone.so` | 反汇编 |
| `libkeystone.so` | 汇编 |
| `libunicorn.so` | CPU 模拟（unidbg 的 unicorn2 JNI 桥） |
| `libjnidispatch.so` | JNA |

### 64 位 / 32 位差异

| ABI | capstone | keystone | jnidispatch | unicorn |
| --- | --- | --- | --- | --- |
| arm64-v8a | ✅ | ✅ | ✅ | ✅ |
| x86_64 | ✅ | ✅ | ✅ | ✅ |
| armeabi-v7a | ✅ | ✅ | ✅ | ❌ |
| x86 | ✅ | ✅ | ✅ | ❌ |

32 位不提供 `libunicorn.so`：unicorn/QEMU 需要 `__uint128_t`，Android NDK
的 32 位工具链不提供。`app/build.gradle.kts` 在打包阶段就对 32 位 ABI
剔除该库。

### ABI 判定坑（已修）

旧代码：

```kotlin
val is64 = primary?.endsWith("64")     // ❌
```

`arm64-v8a` 的结尾是 `v8a` 不是 `64`，所以恒为 `false` —— 所有 arm64
真机都被当成 32 位，跳过 `libunicorn.so` 加载，误报
「not available on 32-bit Android targets」。`x86_64` 反而能过。

现在：

```kotlin
val is64 = primary?.contains("64")     // ✅
```

`os.arch` 的 fallback 仍用 `endsWith("64")`（其值是 `aarch64` / `x86_64`，成立）。

### 不可用时的行为

| 场景 | 返回 |
| --- | --- |
| `libkeystone.so` 缺失 | `EMULATOR_UNAVAILABLE` + 明确说缺哪个库 |
| 32 位设备 + unicorn | `EMULATOR_UNAVAILABLE` + 说明 32 位不提供 unicorn |
| `emulationEnabled = false` | `EMULATION_DISABLED` |

`system_control(action=status)` 的 `emulation.setup` 会标
`requires-extra-install`，不会伪装成可用。

### 本地 debug 包 vs CI 包

| 包类型 | `libkeystone.so` | Unidbg |
| --- | --- | --- |
| CI release（`build.yml`） | ✅ 6.5 MB | 可用 |
| 本地 debug（submodule 空） | ❌ | `EMULATOR_UNAVAILABLE` |

本地要完整 Unidbg 必须先跑 `bash build-unidbg-native.sh`（会就地 sed 修
submodule 的 CMakeLists，所以主仓库常显示 submodule dirty，属预期）。

## Frida 后端

`FridaBridge` 是**纯 Kotlin 实现的 frida 协议客户端**（TCP + D-Bus 消息），
不依赖 frida-java / frida-python。

```text
frida_status        → 服务是否可达
frida_sessions      → 当前会话
frida_open          → 附加到进程
frida_hook          → 用 JS agent（Interceptor / Module）挂钩函数
frida_call          → 触发调用并回读 hook 结果
frida_read          → 读内存
frida_write         → 写内存
frida_replace       → 用 JS 片段替换函数实现
frida_modules       → 枚举已加载模块
frida_script        → 在 agent 内执行任意 JS
frida_backtrace     → 取回溯
```

参数支持：整数、`0x…` 十六进制指针、自动 `allocUtf8String` 的字符串。

### 前置条件

- 已 root 的设备
- 设备上装好 `frida-server` 或 `frida-gadget`
- `SettingsStore.fridaPort`（默认 27042）能连通

未就绪时 `dynamic_api(capabilities)` 的 `status.fridaAvailable` 为 `false`。

> **本项目不使用二进制 frida-server 做自动注入。** 用户曾遇到
> frida-server 导致设备死机。这里只是提供一个客户端，是否使用由用户决定。

## Unidbg 会话生命周期

```text
unidbg_session(action="open", workspaceId="ws_...", editSessionId="...")
  → 返回 emulatorSessionId
unidbg_session(action="call", emulatorSessionId="...", symbolName="JNI_OnLoad", args=[...])
  → 返回 {returnValue, registers, ...}
unidbg_session(action="registers", emulatorSessionId="...")
unidbg_session(action="dump", emulatorSessionId="...", addr="0x...", size=256)
unidbg_session(action="memory_maps", emulatorSessionId="...")
unidbg_session(action="trace_code", emulatorSessionId="...", begin="0x...", end="0x...")
unidbg_session(action="breakpoint_add", emulatorSessionId="...", addr="0x...")
unidbg_session(action="single_step", emulatorSessionId="...", count=1)
unidbg_session(action="close", emulatorSessionId="...")
```

会话存在 `EngineRuntime.emulatorSessions` 里，`so_close` 时会清理。

## unidbg_batch

串行批量流水线，支持 `${key.path}` 占位符引用前一步结果：

```json
{
  "steps": [
    {"op": "session_open", "resultKey": "s"},
    {"op": "session_call", "args": ["${s.emulatorSessionId}", "JNI_OnLoad"]},
    {"op": "session_dump", "args": ["${s.emulatorSessionId}", "0x1000", "64"]}
  ],
  "stopOnError": true,
  "maxSteps": 30
}
```

## 写临时文件

`UnidbgEmulator.writeTemp` 把内存里的 SO 落到 `cache/unidbg/emu_*.so`：

```kotlin
private fun writeTemp(bytes: ByteArray, symbolName: String): File {
    val dir = File(context.cacheDir, "unidbg").apply { mkdirs() }
    // 【修复】deleteOnExit() 在 Android 上不可靠（进程被 kill 时不执行），
    // 写之前先清理 5 分钟前的旧文件
    pruneStaleTempFiles(dir, keepMs = 5 * 60 * 1000L)
    f.deleteOnExit()
    return f
}
```

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 ABI 判定坑与 32 位 unicorn 缺失原因 |
