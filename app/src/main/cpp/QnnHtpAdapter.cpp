#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "QnnHtpAdapter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Status codes must match Kotlin object QnnHtpAdapter
constexpr int STATUS_UNAVAILABLE = 0;
constexpr int STATUS_AVAILABLE = 1;
constexpr int STATUS_INITIALIZED = 2;

constexpr int INIT_OK = 0;
constexpr int INIT_MISSING_SDK = 1;
constexpr int INIT_UNSUPPORTED_DEVICE = 2;
constexpr int INIT_FAILED = -1;

static bool g_initialized = false;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_domain_engine_QnnHtpAdapter_nativeGetHtpStatus(JNIEnv* env, jobject /*thiz*/) {
    // Honest: without QNN SDK we cannot query HTP. Report unavailable.
#ifdef QNN_AVAILABLE
    // Real implementation would:
    // - Load libQnnSystem.so via QnnSystemInterface
    // - Query Hexagon version, check SM7325-AF support
    // For now, even with flag, we keep safe stub to avoid claiming unverified execution.
    // TODO: integrate QNN SDK headers <QnnInterface.h> when available.
    LOGW("QNN_AVAILABLE set but integration not finished - reporting unavailable until validated on device");
    return STATUS_UNAVAILABLE;
#else
    LOGI("QNN SDK not available at compile time - HTP unavailable (expected in v0.1)");
    return STATUS_UNAVAILABLE;
#endif
}

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_domain_engine_QnnHtpAdapter_nativeInitQnn(JNIEnv* env, jobject /*thiz*/) {
#ifdef QNN_AVAILABLE
    if (g_initialized) return INIT_OK;
    // Real init:
    // - dlopen libQnnHtp.so
    // - QnnInterface_getProviders
    // - Create context for Hexagon
    // Without validated artifacts, we must not claim success.
    LOGW("QNN init requested but HTP integration requires DLC artifacts for SM7325-AF");
    return INIT_MISSING_SDK;
#else
    return INIT_MISSING_SDK;
#endif
}

JNIEXPORT void JNICALL
Java_com_slop4hexagon_domain_engine_QnnHtpAdapter_nativeShutdown(JNIEnv* env, jobject /*thiz*/) {
    g_initialized = false;
    LOGI("QNN shutdown");
}

} // extern "C"
