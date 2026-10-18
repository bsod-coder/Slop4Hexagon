// JNI bridge for the portable CPU micro-op engine.
//
// Contract with the Kotlin side (com.slop4hexagon.app.nativebridge.SlopNativeBridge):
//   * every micro-op function returns 0 (slop::kOk) on success and a negative
//     slop::StatusCode on failure; the result digest is written to element 0 of
//     the caller-provided one-element DoubleArray. Errors are never signalled
//     through the value channel, because the digest is a signed real number;
//   * primitive array elements are read with Get<Prim>ArrayElements and released
//     with JNI_ABORT, so inputs are never written back;
//   * declared tensor sizes are validated against the actual array lengths
//     before any pointer is dereferenced;
//   * nothing is thrown across the boundary and no Java exception can escape a
//     kernel (kernels only use bounded pointer arithmetic);
//   * no output tensor is materialized on the benchmark path and nothing is
//     allocated on the Java heap besides the 8-byte digest slot.
#include <jni.h>

#include <cstddef>
#include <cstdint>

#include "slop_engine.h"

namespace {

bool prepareDigest(JNIEnv *env, jdoubleArray outDigest, jdouble *scratch) {
    if (outDigest == nullptr || env->GetArrayLength(outDigest) < 1) return false;
    *scratch = 0.0;
    return true;
}

void publishDigest(JNIEnv *env, jdoubleArray outDigest, double value, int32_t rc) {
    if (rc == slop::kOk && outDigest != nullptr && env->GetArrayLength(outDigest) >= 1) {
        env->SetDoubleArrayRegion(outDigest, 0, 1, &value);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_app_nativebridge_SlopNativeBridge_nativeEngineVersion(JNIEnv *, jobject) {
    return slop_engine_version();
}

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_app_nativebridge_SlopNativeBridge_nativeSelfTest(JNIEnv *, jobject, jint iterations) {
    return slop_self_test(iterations);
}

JNIEXPORT jlong JNICALL
Java_com_slop4hexagon_app_nativebridge_SlopNativeBridge_nativePeakRssBytes(JNIEnv *, jobject) {
    return static_cast<jlong>(slop_peak_rss_bytes());
}

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_app_nativebridge_SlopNativeBridge_nativeMatmulF32(
        JNIEnv *env, jobject, jfloatArray a, jfloatArray b, jint m, jint k, jint n, jdoubleArray outDigest) {
    double digestValue = 0.0;
    if (!prepareDigest(env, outDigest, &digestValue)) return slop::kErrBadDimensions;
    if (a == nullptr || b == nullptr) return slop::kErrNullPointer;
    const jint aLen = env->GetArrayLength(a);
    const jint bLen = env->GetArrayLength(b);
    if (m <= 0 || k <= 0 || n <= 0) return slop::kErrBadDimensions;
    const int64_t needA = static_cast<int64_t>(m) * k;
    const int64_t needB = static_cast<int64_t>(k) * n;
    if (needA > aLen || needB > bLen) return slop::kErrBufferTooSmall;

    float *pa = env->GetFloatArrayElements(a, nullptr);
    if (pa == nullptr) return slop::kErrNullPointer;
    float *pb = env->GetFloatArrayElements(b, nullptr);
    if (pb == nullptr) {
        env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
        return slop::kErrNullPointer;
    }
    const int32_t rc = slop_matmul_f32_checksum(pa, pb, m, k, n, &digestValue);
    env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
    env->ReleaseFloatArrayElements(b, pb, JNI_ABORT);
    publishDigest(env, outDigest, digestValue, rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_app_nativebridge_SlopNativeBridge_nativeMatmulInt8(
        JNIEnv *env, jobject, jbyteArray a, jbyteArray b, jint m, jint k, jint n, jdoubleArray outDigest) {
    double digestValue = 0.0;
    if (!prepareDigest(env, outDigest, &digestValue)) return slop::kErrBadDimensions;
    if (a == nullptr || b == nullptr) return slop::kErrNullPointer;
    const jint aLen = env->GetArrayLength(a);
    const jint bLen = env->GetArrayLength(b);
    if (m <= 0 || k <= 0 || n <= 0) return slop::kErrBadDimensions;
    const int64_t needA = static_cast<int64_t>(m) * k;
    const int64_t needB = static_cast<int64_t>(k) * n;
    if (needA > aLen || needB > bLen) return slop::kErrBufferTooSmall;

    int8_t *pa = reinterpret_cast<int8_t *>(env->GetByteArrayElements(a, nullptr));
    if (pa == nullptr) return slop::kErrNullPointer;
    int8_t *pb = reinterpret_cast<int8_t *>(env->GetByteArrayElements(b, nullptr));
    if (pb == nullptr) {
        env->ReleaseByteArrayElements(a, reinterpret_cast<jbyte *>(pa), JNI_ABORT);
        return slop::kErrNullPointer;
    }
    const int32_t rc = slop_matmul_i8_checksum(pa, pb, m, k, n, &digestValue);
    env->ReleaseByteArrayElements(a, reinterpret_cast<jbyte *>(pa), JNI_ABORT);
    env->ReleaseByteArrayElements(b, reinterpret_cast<jbyte *>(pb), JNI_ABORT);
    publishDigest(env, outDigest, digestValue, rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_app_nativebridge_SlopNativeBridge_nativeConv2dF32(
        JNIEnv *env, jobject, jfloatArray input, jfloatArray weights, jfloatArray bias,
        jint c, jint h, jint w, jint kc, jdoubleArray outDigest) {
    double digestValue = 0.0;
    if (!prepareDigest(env, outDigest, &digestValue)) return slop::kErrBadDimensions;
    if (input == nullptr || weights == nullptr) return slop::kErrNullPointer;
    if (c <= 0 || h <= 0 || w <= 0 || kc <= 0) return slop::kErrBadDimensions;
    const int64_t needIn = static_cast<int64_t>(c) * h * w;
    const int64_t needW = static_cast<int64_t>(kc) * c * 9;
    if (needIn > env->GetArrayLength(input) || needW > env->GetArrayLength(weights)) return slop::kErrBufferTooSmall;
    if (bias != nullptr && env->GetArrayLength(bias) < kc) return slop::kErrBufferTooSmall;

    float *pi = env->GetFloatArrayElements(input, nullptr);
    if (pi == nullptr) return slop::kErrNullPointer;
    float *pw = env->GetFloatArrayElements(weights, nullptr);
    if (pw == nullptr) {
        env->ReleaseFloatArrayElements(input, pi, JNI_ABORT);
        return slop::kErrNullPointer;
    }
    float *pb = nullptr;
    if (bias != nullptr) {
        pb = env->GetFloatArrayElements(bias, nullptr);
        if (pb == nullptr) {
            env->ReleaseFloatArrayElements(input, pi, JNI_ABORT);
            env->ReleaseFloatArrayElements(weights, pw, JNI_ABORT);
            return slop::kErrNullPointer;
        }
    }
    const int32_t rc = slop_conv2d_3x3_f32_checksum(pi, pw, pb, c, h, w, kc, &digestValue);
    if (pb != nullptr) env->ReleaseFloatArrayElements(bias, pb, JNI_ABORT);
    env->ReleaseFloatArrayElements(input, pi, JNI_ABORT);
    env->ReleaseFloatArrayElements(weights, pw, JNI_ABORT);
    publishDigest(env, outDigest, digestValue, rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_slop4hexagon_app_nativebridge_SlopNativeBridge_nativeEltwiseF32(
        JNIEnv *env, jobject, jfloatArray a, jfloatArray b, jboolean multiply, jdoubleArray outDigest) {
    double digestValue = 0.0;
    if (!prepareDigest(env, outDigest, &digestValue)) return slop::kErrBadDimensions;
    if (a == nullptr || b == nullptr) return slop::kErrNullPointer;
    const jint aLen = env->GetArrayLength(a);
    const jint bLen = env->GetArrayLength(b);
    if (aLen <= 0 || bLen <= 0) return slop::kErrBadDimensions;
    const jint n = aLen < bLen ? aLen : bLen;

    float *pa = env->GetFloatArrayElements(a, nullptr);
    if (pa == nullptr) return slop::kErrNullPointer;
    float *pb = env->GetFloatArrayElements(b, nullptr);
    if (pb == nullptr) {
        env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
        return slop::kErrNullPointer;
    }
    const int32_t rc = slop_eltwise_f32_checksum(pa, pb, n, multiply ? 1 : 0, &digestValue);
    env->ReleaseFloatArrayElements(a, pa, JNI_ABORT);
    env->ReleaseFloatArrayElements(b, pb, JNI_ABORT);
    publishDigest(env, outDigest, digestValue, rc);
    return rc;
}

}  // extern "C"
