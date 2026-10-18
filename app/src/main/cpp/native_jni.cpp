// JNI bridge for the portable CPU micro-op engine.
//
// Contract with the Kotlin side (com.slop4hexagon.app.nativebridge.NativeCpuEngine):
//   * every checksum-returning function returns a non-negative double on
//     success (a 64-bit reduction of the output tensor) and a *negative* value
//     on failure, which equals the slop::StatusCode of the failure;
//   * primitive array elements are read with Get<Prim>ArrayElements and released
//     with JNI_ABORT, so inputs are never written back;
//   * no C++ exception can cross the boundary; every entry point is noexcept
//     in practice because kernels use only bounded pointer arithmetic;
//   * nothing here allocates on the Java heap and no output tensor is
//     materialized for the benchmark path.
#include <jni.h>

#include <cstddef>
#include <cstdint>

#include "slop_engine.h"

extern "C" {

JNIEXPORT jint JNICALL Java_com_slop4hexagon_app_nativebridge_NativeCpuEngine_nativeEngineVersion(JNIEnv *, jobject) {
    return slop_engine_version();
}

JNIEXPORT jint JNICALL Java_com_slop4hexagon_app_nativebridge_NativeCpuEngine_nativeSelfTest(JNIEnv *, jobject, jint iterations) {
    return slop_self_test(iterations);
}

JNIEXPORT jlong JNICALL Java_com_slop4hexagon_app_nativebridge_NativeCpuEngine_nativePeakRssBytes(JNIEnv *, jobject) {
    return static_cast<jlong>(slop_peak_rss_bytes());
}

JNIEXPORT jdouble JNICALL Java_com_slop4hexagon_app_nativebridge_NativeCpuEngine_nativeMatmulF32(JNIEnv *env, jobject, jfloatArray a, jfloatArray b, jint m, jint k, jint n) {
    if (a == nullptr || b == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    const jint aLen = env->GetArrayLength(a);
    const jint bLen = env->GetArrayLength(b);
    const int64_t needA = static_cast<int64_t>(m) * static_cast<int64_t>(k);
    const int64_t needB = static_cast<int64_t>(k) * static_cast<int64_t>(n);
    if (m <= 0 || k <= 0 || n <= 0) return static_cast<jdouble>(slop::kErrBadDimensions);
    if (needA > static_cast<int64_t>(aLen) || needB > static_cast<int64_t>(bLen)) return static_cast<jdouble>(slop::kErrBufferTooSmall);

    float *pa = env->GetFloatArrayElements(a, nullptr);
    if (pa == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    float *pb = env->GetFloatArrayElements(b, nullptr);
    if (pb == nullptr) {
        env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
        return static_cast<jdouble>(slop::kErrNullPointer);
    }
    double checksum = 0.0;
    const int32_t rc = slop_matmul_f32_checksum(pa, pb, m, k, n, &checksum);
    env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
    env->ReleaseFloatArrayElements(b, pb, JNI_ABORT);
    if (rc != slop::kOk) return static_cast<jdouble>(rc);
    return static_cast<jdouble>(checksum);
}

JNIEXPORT jdouble JNICALL Java_com_slop4hexagon_app_nativebridge_NativeCpuEngine_nativeMatmulInt8(JNIEnv *env, jobject, jbyteArray a, jbyteArray b, jint m, jint k, jint n) {
    if (a == nullptr || b == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    const jint aLen = env->GetArrayLength(a);
    const jint bLen = env->GetArrayLength(b);
    const int64_t needA = static_cast<int64_t>(m) * static_cast<int64_t>(k);
    const int64_t needB = static_cast<int64_t>(k) * static_cast<int64_t>(n);
    if (m <= 0 || k <= 0 || n <= 0) return static_cast<jdouble>(slop::kErrBadDimensions);
    if (needA > static_cast<int64_t>(aLen) || needB > static_cast<int64_t>(bLen)) return static_cast<jdouble>(slop::kErrBufferTooSmall);

    int8_t *pa = reinterpret_cast<int8_t *>(env->GetByteArrayElements(a, nullptr));
    if (pa == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    int8_t *pb = reinterpret_cast<int8_t *>(env->GetByteArrayElements(b, nullptr));
    if (pb == nullptr) {
        env->ReleaseByteArrayElements(a, reinterpret_cast<jbyte *>(pa), JNI_ABORT);
        return static_cast<jdouble>(slop::kErrNullPointer);
    }
    double checksum = 0.0;
    const int32_t rc = slop_matmul_i8_checksum(pa, pb, m, k, n, &checksum);
    env->ReleaseByteArrayElements(a, reinterpret_cast<jbyte *>(pa), JNI_ABORT);
    env->ReleaseByteArrayElements(b, reinterpret_cast<jbyte *>(pb), JNI_ABORT);
    if (rc != slop::kOk) return static_cast<jdouble>(rc);
    return static_cast<jdouble>(checksum);
}

JNIEXPORT jdouble JNICALL Java_com_slop4hexagon_app_nativebridge_NativeCpuEngine_nativeConv2dF32(JNIEnv *env, jobject, jfloatArray input, jfloatArray weights, jfloatArray bias, jint c, jint h, jint w, jint kc) {
    if (input == nullptr || weights == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    const jint inLen = env->GetArrayLength(input);
    const jint wLen = env->GetArrayLength(weights);
    const int64_t needIn = static_cast<int64_t>(c) * h * w;
    const int64_t needW = static_cast<int64_t>(kc) * c * 9;
    if (c <= 0 || h <= 0 || w <= 0 || kc <= 0) return static_cast<jdouble>(slop::kErrBadDimensions);
    if (needIn > static_cast<int64_t>(inLen) || needW > static_cast<int64_t>(wLen)) return static_cast<jdouble>(slop::kErrBufferTooSmall);

    float *pi = env->GetFloatArrayElements(input, nullptr);
    if (pi == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    float *pw = env->GetFloatArrayElements(weights, nullptr);
    if (pw == nullptr) {
        env->ReleaseFloatArrayElements(input, pi, JNI_ABORT);
        return static_cast<jdouble>(slop::kErrNullPointer);
    }
    float *pb = nullptr;
    if (bias != nullptr) {
        if (env->GetArrayLength(bias) < kc) {
            env->ReleaseFloatArrayElements(input, pi, JNI_ABORT);
            env->ReleaseFloatArrayElements(weights, pw, JNI_ABORT);
            return static_cast<jdouble>(slop::kErrBufferTooSmall);
        }
        pb = env->GetFloatArrayElements(bias, nullptr);
        if (pb == nullptr) {
            env->ReleaseFloatArrayElements(input, pi, JNI_ABORT);
            env->ReleaseFloatArrayElements(weights, pw, JNI_ABORT);
            return static_cast<jdouble>(slop::kErrNullPointer);
        }
    }
    double checksum = 0.0;
    const int32_t rc = slop_conv2d_3x3_f32_checksum(pi, pw, pb, c, h, w, kc, &checksum);
    if (bias != nullptr && pb != nullptr) env->ReleaseFloatArrayElements(bias, pb, JNI_ABORT);
    env->ReleaseFloatArrayElements(input, pi, JNI_ABORT);
    env->ReleaseFloatArrayElements(weights, pw, JNI_ABORT);
    if (rc != slop::kOk) return static_cast<jdouble>(rc);
    return static_cast<jdouble>(checksum);
}

JNIEXPORT jdouble JNICALL Java_com_slop4hexagon_app_nativebridge_NativeCpuEngine_nativeEltwiseF32(JNIEnv *env, jobject, jfloatArray a, jfloatArray b, jboolean multiply) {
    if (a == nullptr || b == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    const jint aLen = env->GetArrayLength(a);
    const jint bLen = env->GetArrayLength(b);
    if (aLen <= 0 || bLen <= 0) return static_cast<jdouble>(slop::kErrBadDimensions);
    const jint n = aLen < bLen ? aLen : bLen;

    float *pa = env->GetFloatArrayElements(a, nullptr);
    if (pa == nullptr) return static_cast<jdouble>(slop::kErrNullPointer);
    float *pb = env->GetFloatArrayElements(b, nullptr);
    if (pb == nullptr) {
        env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
        return static_cast<jdouble>(slop::kErrNullPointer);
    }
    double checksum = 0.0;
    const int32_t rc = slop_eltwise_f32_checksum(pa, pb, n, multiply ? 1 : 0, &checksum);
    env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
    env->ReleaseFloatArrayElements(b, pb, JNI_ABORT);
    if (rc != slop::kOk) return static_cast<jdouble>(rc);
    return static_cast<jdouble>(checksum);
}

}  // extern "C"
