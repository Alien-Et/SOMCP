#include <jni.h>
#include <dlfcn.h>
#include <cctype>
#include <mutex>
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>

// Compiles the AGPL-3.0 license text into this library. Exactly one
// translation unit per shared library may include it; see license_notice.h.
#include "license_notice.h"

namespace {
using RunFn = int (*)(int, int, int, const char*, volatile int*);
std::mutex cancel_mutex;
std::unordered_map<long long, std::shared_ptr<int>> cancellations;
std::unordered_set<long long> pending_cancellations;

void throw_runtime(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

bool valid_library(const std::string& value) {
    if (value.empty() || value.size() > 128 || value.rfind("blutter_", 0) != 0) return false;
    for (char ch : value) if (!std::isalnum(static_cast<unsigned char>(ch)) && ch != '_') return false;
    return true;
}

// 【新增】本地 runner 的绝对路径校验。
//
// 内置 runner 是 `lib<name>.so`、由 linker 在 namespace 里搜索（即 APK 的
// native lib 目录）。而按 Dart 版本拆分的 runner 是**运行时按需下载**到应用
// 私有目录的，必须用绝对路径 dlopen —— 这条路在 Android 10+ 上是允许的
// （受限的是外部存储等"其他方可能篡改"的位置；应用自己的 files/ 目录可加载）。
//
// 这里只做**形式**校验。真正的边界由 Kotlin 侧保证：只有位于
// `filesDir/blutter-engines/` 之下的路径才会被传下来。
bool valid_runner_path(const std::string& value) {
    if (value.size() < 5 || value.size() > 512) return false;
    if (value.front() != '/') return false;
    if (value.compare(value.size() - 3, 3, ".so") != 0) return false;
    if (value.find("..") != std::string::npos) return false;
    for (char ch : value) {
        if (!std::isalnum(static_cast<unsigned char>(ch)) && ch != '_' && ch != '-' &&
            ch != '.' && ch != '/') {
            return false;
        }
    }
    return true;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_soreverse_mcp_blutter_NativeBlutterBridge_nativeRun(JNIEnv* env, jobject, jstring library_name, jint libapp_fd, jint libflutter_fd, jint result_fd, jstring options_json, jlong token) {
    auto cancelled = std::make_shared<int>(0);
    {
        std::lock_guard<std::mutex> lock(cancel_mutex);
        if (pending_cancellations.erase(token) > 0) __atomic_store_n(cancelled.get(), 1, __ATOMIC_RELEASE);
        cancellations[token] = cancelled;
    }
    struct Registration {
        long long token;
        ~Registration() {
            std::lock_guard<std::mutex> lock(cancel_mutex);
            cancellations.erase(token);
            pending_cancellations.erase(token);
        }
    } registration{token};
    if (library_name == nullptr || options_json == nullptr || libapp_fd < 0 || libflutter_fd < 0 || result_fd < 0) {
        throw_runtime(env, "Invalid Blutter runner arguments");
        return -1;
    }
    const char* raw_library = env->GetStringUTFChars(library_name, nullptr);
    const char* raw_options = env->GetStringUTFChars(options_json, nullptr);
    if (raw_library == nullptr || raw_options == nullptr) return -1;
    std::string library(raw_library);
    env->ReleaseStringUTFChars(library_name, raw_library);
    // 两种 runner 形态：
    //   1. 内置：库名（如 blutter_5e4949e6…）-> dlopen("lib<name>.so") 走 namespace 搜索
    //   2. 本地：应用私有目录下的绝对路径（按 Dart 版本下载的 runner）-> 直接 dlopen
    std::string soname;
    if (!library.empty() && library.front() == '/') {
        if (!valid_runner_path(library)) {
            env->ReleaseStringUTFChars(options_json, raw_options);
            throw_runtime(env, "Rejected Blutter runner path");
            return -1;
        }
        soname = library;
    } else {
        if (!valid_library(library)) {
            env->ReleaseStringUTFChars(options_json, raw_options);
            throw_runtime(env, "Rejected Blutter runner library name");
            return -1;
        }
        soname = "lib" + library + ".so";
    }
    void* handle = dlopen(soname.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        env->ReleaseStringUTFChars(options_json, raw_options);
        const char* error = dlerror();
        throw_runtime(env, error == nullptr ? "Cannot load Blutter runner" : error);
        return -1;
    }
    auto run = reinterpret_cast<RunFn>(dlsym(handle, "blutter_run_fd"));
    if (run == nullptr) {
        env->ReleaseStringUTFChars(options_json, raw_options);
        dlclose(handle);
        throw_runtime(env, "Blutter runner does not export blutter_run_fd");
        return -1;
    }
    int result = run(libapp_fd, libflutter_fd, result_fd, raw_options, cancelled.get());
    env->ReleaseStringUTFChars(options_json, raw_options);
    dlclose(handle);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_soreverse_mcp_blutter_NativeBlutterBridge_nativeCancel(JNIEnv*, jobject, jlong token) {
    std::lock_guard<std::mutex> lock(cancel_mutex);
    auto found = cancellations.find(token);
    if (found != cancellations.end()) {
        __atomic_store_n(found->second.get(), 1, __ATOMIC_RELEASE);
    } else {
        pending_cancellations.insert(token);
    }
}
