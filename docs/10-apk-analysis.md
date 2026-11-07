# 10 · APK 分析

对应源码：`engine/ApkAnalyzer.kt`（585 行）、`ApkSigTools.kt`（647 行）、
`EngineRuntimeApkAudit.kt`、`EngineRuntimeSources.kt`（`analyzeApk` / `openUrl`）

## 五条入口

| 入口 | 触发条件 | parser 标识 |
| --- | --- | --- |
| `analyzeFile(File)` | `File.canRead()` 为真 | `builtin_apk_zip_streaming` |
| `analyzeRootStream(path)` | 不可直读 + root 可用 | `builtin_apk_zip_root_stream` |
| `analyze(bytes)` | 从工作目录（SAF）读出字节 | `builtin_apk_zip_dex_axml` |
| `apk_*` 审计（4 个） | 见下文 | — |
| `flutter_blutter(action=inspect)` | Flutter 专用 | — |

`analyzeApk` 的调度顺序：

```kotlin
val local = File(path)
val directReadable = local.isFile && local.canRead()

if (directReadable) {
    // 优先级 1：随机访问，解析完整
    return ok(ApkAnalyzer.analyzeFile(local, path, entryLimit))
}
if (RootShell.isAvailable()) {
    // 优先级 2：root 流，只开一次
    return ok(ApkAnalyzer.analyzeRootStream(path, entryLimit))
}
// 优先级 3：SAF 工作目录
```

## 三条解析路径的共同行为

### ZIP magic 校验

三条入口都先读 4 字节确认是 ZIP：

```kotlin
if (magicLen < 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4b.toByte()) {
    throw ApkNotZipException(
        "$path is not a ZIP/APK file (magic=${...joinToString("") { "%02x".format(it) }})"
    )
}
```

`ApkNotZipException` 在 `guarded` 里映射成 `APK_INVALID`，
`badValue` 是原始路径，`message` 带实际 magic 值。

**为什么重要**：旧实现 root 分支在 magic 校验之前就 `return` 了，
而 `analyzeRootStream` 自己没校验 —— 传 `.so` / 文本 / 目录 / 空文件
都会返回 `ok:true` + `entryCount:0`，AI 客户端会以为分析成功。

### 真实解析 manifest / dex

```kotlin
val shouldParse = !entry.isDirectory &&
    (name == "AndroidManifest.xml" ||
        name.matches(Regex("^classes(?:[2-9][0-9]*)?\\.dex$")))

if (shouldParse) {
    val data = readEntry(zip, name, limits, parsedBytes)
    parsedBytes += data.size
    if (name == "AndroidManifest.xml") {
        manifest = parseManifestSummary(data)
    } else {
        dexFiles.put(parseDexHeader(name, data))
    }
}
```

DEX 头解析出：

```json
{
  "entry": "classes.dex",
  "size": 4672208,
  "magic": "dex\n038\\0",
  "valid": true,
  "fileSize": 4672208,
  "headerSize": 112,
  "endianTag": "0x12345678",
  "stringIds": 63987,
  "typeIds": 8123,
  "protoIds": 4321,
  "fieldIds": 12345,
  "methodIds": 32842,
  "classDefs": 5678
}
```

`manifest` 解析出 `present` / `size` / `format`（`android_binary_xml` 或
`text_xml`）/ `textPreview`。

### 全文件 sha256

**这里有一个必须理解的坑**：`ZipInputStream` 读到 central directory
就停了，不会把文件读完。如果直接用 `DigestInputStream` 包住它，
摘要只覆盖「前 N 字节」，而且 N 随内部缓冲预读量漂移 —— 同一个 386MB 包
两次调用会得到两个不同的 hash。

正确做法（现在的实现）：

```kotlin
stream.use { input ->
    val counting = CountingInputStream(input) { n, buf, off -> digest.update(buf, off, n) }
    val buffered = BufferedInputStream(counting, 64 * 1024)
    // ... magic 校验 ...

    // 【关键】ZipInputStream.close() 会关掉它包装的流，用 NonClosing 包装
    val nonClosing = NonClosingInputStream(buffered)
    val replay = SequenceInputStream(ByteArrayInputStream(magic, 0, magicLen), nonClosing)
    ZipInputStream(replay).use { zip ->
        // 遍历条目 ...
    }
    // ZIP 遍历结束后，继续把剩余字节读完
    val tail = ByteArray(64 * 1024)
    while (true) {
        val n = buffered.read(tail)
        if (n < 0) break
        totalRead += n
    }
}
```

`NonClosingInputStream` 覆写 `close()` 为空 —— 否则 `ZipInputStream.use{}`
结束时会把底层 `buffered` 关掉，紧接着读尾部会抛 `Stream closed`。

## 返回结构

```json
{
  "path": "/data/app/.../base.apk",
  "size": 386766061,
  "sha256": "b3830a5d...",
  "parser": "builtin_apk_zip_root_stream",
  "externalApkMcpRequired": false,
  "entryCount": 14473,
  "entriesTruncated": true,
  "entries": [{"name": "...", "size": 4672208, "compressedSize": 1444489, "directory": false}],
  "nativeLibs": [{"name": "lib/arm64-v8a/libfoo.so", "abi": "arm64-v8a", "size": ...}],
  "dexFiles": ["classes5.dex", {"entry": "classes5.dex", "magic": "dex\n038\\0", "methodIds": 32842, ...}],
  "signatures": ["META-INF/MANIFEST.MF", "META-INF/ANDROID.SF", "META-INF/ANDROID.RSA"],
  "abis": ["arm64-v8a"],
  "manifest": {"present": true, "size": 12345, "format": "android_binary_xml"},
  "resourcesArsc": true,
  "flutter": {"detected": false, "mode": "unknown", ...},
  "parsedBytes": 143690824,
  "limitations": [
    "Streaming root path: only AndroidManifest.xml and classes*.dex payloads are parsed",
    "APK Signature Scheme v2/v3/v4 block verification is not included in this parser (use apk_sig_verify)"
  ]
}
```

**注意 `dexFiles` 的混合形态**：先是纯文件名字符串（遍历阶段收集），
后跟解析出的对象（解析阶段追加）。客户端需要同时处理两种形态。

## 4 个 APK 审计工具

| 工具 | 能力 |
| --- | --- |
| `apk_sig_verify` | v2/v3 签名解析 + digest 重算 + 签名验证 |
| `apk_cert_info` | 证书 subject / issuer / 序列号 / 有效期 / 算法 |
| `apk_zip_anomaly` | ZIP 结构异常（重复条目、越界、非标准压缩） |
| `apk_abi_coverage` | 各 ABI 的 SO 覆盖情况 |

### 堆预算保护

这 4 个工具需要**整包随机访问**（ZIP central directory 在文件末尾，
签名块也在末尾）。旧实现一律 `readBytes()` 整包进堆，386MB 包占 386MB 堆。

现在读入前先按真实堆余量检查：

```kotlin
private fun heapHeadroomBytes(): Long {
    val rt = Runtime.getRuntime()
    return (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())).coerceAtLeast(0)
}

if (declaredSize > heapHeadroomBytes() * AUDIT_MAX_HEAP_FRACTION) {
    return err(
        "APK_TOO_LARGE_FOR_AUDIT",
        "APK is 368 MiB but only 221 MiB of heap headroom is available for a whole-file audit. " +
            "Use apk_analyze (streaming) instead, or free memory and retry."
    )
}
```

`AUDIT_MAX_HEAP_FRACTION = 0.45`。

root-only 路径会先流式落一份临时文件到 `cache/apk-audit/`，读完即删。

## open_url

```text
so_open(action="open_url", url="https://.../libfoo.so", outputName="libfoo.so")
```

流程：

1. URL 白名单：拒绝 loopback / link-local / site-local / multicast
2. 只允许 `http` / `https`
3. 下载上限 8 GiB（`soDownloadMaxBytes`）
4. 校验下载内容是 ELF（4 字节 magic）
5. 落盘：
   - 有 SAF 工作目录 → 写工作目录
   - **没有 → 写 App 外部私有目录 `files/downloads/`**

第 5 步是本分支的修复：旧实现强制要求 SAF 工作目录，而工作目录 UI
已经删了，`open_url` 永久不可用。

## 大包实测数据

BeautyPlus 386,766,061 字节（14,473 条目）：

| 路径 | 耗时 | sha256 正确 | manifest | parsedBytes |
| --- | --- | --- | --- | --- |
| `analyzeFile`（直读） | ~4s | ✅ | ✅ | 143,690,824 |
| `analyzeRootStream`（root 流） | ~21s | ✅ | ✅ | 143,690,824 |

root 流慢的原因是纯管道无法 seek，必须顺序读完整个包。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-07 | 建立文档；记录 sha256 截断坑与 NonClosingInputStream 的必要性 |
