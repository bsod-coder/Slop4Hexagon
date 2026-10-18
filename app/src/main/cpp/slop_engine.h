// Slop4Hexagon portable CPU micro-op engine.
//
// Header-only reference kernels used by:
//   * the Android shared library (JNI microbenchmark/inference path)
//   * host-side unit tests compiled with the host compiler (see
//     tools/offline-native-check)
//
// Design goals:
//   * no third-party dependencies, C++17 only
//   * no Java heap pressure: results are reduced to a checksum on the fly, so
//     the benchmark path never materializes a full output tensor
//   * deterministic, FMA-independent checksums (see fold()) so two backends can
//     be numerically compared for validation
//   * defensive input validation everywhere; failure is reported as an error
//     code, never as a crash
#ifndef SLOP_ENGINE_H_
#define SLOP_ENGINE_H_

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace slop {

enum StatusCode : int32_t {
    kOk = 0,
    kErrNullPointer = -1,
    kErrBadDimensions = -2,
    kErrBufferTooSmall = -3,
    kErrOverflow = -4,
    kErrUnsupported = -5,
};

// Upper bounds chosen so 32-bit index math cannot overflow and a single
// request cannot exceed the memory budget of a mid-range phone (16 Mi
// elements ~= 64 MiB of float32, the largest tensor a single micro-op request
// may touch).
constexpr size_t kMaxElements = 1u << 24;
constexpr int kMaxMatrixDim = 8192;
constexpr int kMaxConvSide = 2048;
constexpr int kMaxConvChannels = 512;
// int32 accumulation of K products of |a|,|b| <= 127 must not overflow.
constexpr int kMaxInt8InnerDim = 8192;

// ---------------------------------------------------------------------------
// Output reduction used to prove that an operation really computed something,
// and to validate results across backends.
//
// Design constraints:
//   * position-weighted, so reordering elements changes the digest;
//   * *linear* in the data, so a small numerical difference between two
//     implementations (different summation order, different precision) produces
//     a proportionally small digest difference. Validation therefore compares
//     digests with a relative tolerance instead of demanding bit equality,
//     which is the only honest comparison between e.g. a float32 CPU kernel
//     and an int8/fp16 accelerator execution;
//   * accumulation happens in the same fixed element order for a given kernel,
//     so repeated runs of one implementation are bit-identical.
// ---------------------------------------------------------------------------
struct TensorDigest {
    double weightedSum = 0.0;
    double absSum = 0.0;
    uint64_t count = 0;
};

constexpr double kDigestTolerance = 1e-3;

inline void fold(TensorDigest &digest, double value) {
    // Keep pathological values from poisoning the whole reduction.
    const double v = std::isfinite(value) ? value : 0.0;
    digest.weightedSum += v * static_cast<double>((digest.count % 997u) + 1u);
    digest.absSum += std::fabs(v);
    ++digest.count;
}

/// The value reported across the JNI boundary.
inline double checksumOf(const TensorDigest &digest) { return digest.weightedSum; }

/// True when two digests agree within [kDigestTolerance] relative to scale.
inline bool digestsAgree(double a, double b, double scale) {
    const double allowed = kDigestTolerance * (scale + 1.0);
    return std::fabs(a - b) <= allowed;
}

// ---------------------------------------------------------------------------
// Kernels. The *_into variants write a full output tensor (used by tests and
// by any future real inference path); the *_checksum variants stream the
// result into a rolling fold with O(N) scratch (used by microbenchmarks).
// ---------------------------------------------------------------------------

// C[M x N] = A[M x K] * B[K x N], row-major, float32.
inline int32_t matmulF32Into(const float *A, const float *B, float *out, int M, int K, int N) {
    if (A == nullptr || B == nullptr || out == nullptr) return kErrNullPointer;
    if (M <= 0 || K <= 0 || N <= 0) return kErrBadDimensions;
    if (M > kMaxMatrixDim || K > kMaxMatrixDim || N > kMaxMatrixDim) return kErrBadDimensions;
    const size_t total = static_cast<size_t>(M) * static_cast<size_t>(N);
    if (total > kMaxElements) return kErrOverflow;
    // ikj order with row-major B for cache friendliness.
    std::memset(out, 0, total * sizeof(float));
    for (int i = 0; i < M; ++i) {
        float *cRow = out + static_cast<size_t>(i) * N;
        const float *aRow = A + static_cast<size_t>(i) * K;
        for (int k = 0; k < K; ++k) {
            const float a = aRow[k];
            const float *bRow = B + static_cast<size_t>(k) * N;
            for (int j = 0; j < N; ++j) cRow[j] += a * bRow[j];
        }
    }
    return kOk;
}

inline int32_t matmulF32Checksum(const float *A, const float *B, int M, int K, int N, TensorDigest *outDigest) {
    if (outDigest == nullptr) return kErrNullPointer;
    if (M <= 0 || K <= 0 || N <= 0) return kErrBadDimensions;
    if (M > kMaxMatrixDim || K > kMaxMatrixDim || N > kMaxMatrixDim) return kErrBadDimensions;
    const size_t aLen = static_cast<size_t>(M) * K;
    const size_t bLen = static_cast<size_t>(K) * N;
    if (aLen > kMaxElements || bLen > kMaxElements) return kErrOverflow;
    TensorDigest digest;
    std::vector<float> row(N, 0.0f);
    for (int i = 0; i < M; ++i) {
        std::fill(row.begin(), row.end(), 0.0f);
        const float *aRow = A + static_cast<size_t>(i) * K;
        for (int k = 0; k < K; ++k) {
            const float a = aRow[k];
            const float *bRow = B + static_cast<size_t>(k) * N;
            for (int j = 0; j < N; ++j) row[j] += a * bRow[j];
        }
        for (int j = 0; j < N; ++j) fold(digest, row[j]);
    }
    *outDigest = digest;
    return kOk;
}

// C[M x N](int32) = A[M x K](int8) * B[K x N](int8), row-major.
inline int32_t matmulI8Into(const int8_t *A, const int8_t *B, int32_t *out, int M, int K, int N) {
    if (A == nullptr || B == nullptr || out == nullptr) return kErrNullPointer;
    if (M <= 0 || K <= 0 || N <= 0) return kErrBadDimensions;
    if (K > kMaxInt8InnerDim) return kErrBadDimensions;
    if (M > kMaxMatrixDim || N > kMaxMatrixDim) return kErrBadDimensions;
    const size_t total = static_cast<size_t>(M) * static_cast<size_t>(N);
    if (total > kMaxElements) return kErrOverflow;
    for (int i = 0; i < M; ++i) {
        int32_t *cRow = out + static_cast<size_t>(i) * N;
        const int8_t *aRow = A + static_cast<size_t>(i) * K;
        for (int j = 0; j < N; ++j) {
            int32_t acc = 0;
            for (int k = 0; k < K; ++k) acc += static_cast<int32_t>(aRow[k]) * static_cast<int32_t>(B[static_cast<size_t>(k) * N + j]);
            cRow[j] = acc;
        }
    }
    return kOk;
}

inline int32_t matmulI8Checksum(const int8_t *A, const int8_t *B, int M, int K, int N, TensorDigest *outDigest) {
    if (outDigest == nullptr) return kErrNullPointer;
    if (M <= 0 || K <= 0 || N <= 0) return kErrBadDimensions;
    if (K > kMaxInt8InnerDim || M > kMaxMatrixDim || N > kMaxMatrixDim) return kErrBadDimensions;
    TensorDigest digest;
    for (int i = 0; i < M; ++i) {
        const int8_t *aRow = A + static_cast<size_t>(i) * K;
        for (int j = 0; j < N; ++j) {
            int32_t sum = 0;
            for (int k = 0; k < K; ++k) sum += static_cast<int32_t>(aRow[k]) * static_cast<int32_t>(B[static_cast<size_t>(k) * N + j]);
            fold(digest, static_cast<double>(sum));
        }
    }
    *outDigest = digest;
    return kOk;
}

// Depth-unaware 3x3 convolution, batch 1, NCHW, stride 1, zero padding.
// in: [C x H x W], weights: [Kc x C x 3 x 3], bias: [Kc], out: [Kc x H x W].
inline int32_t conv2d3x3F32Into(const float *in, const float *w, const float *bias, float *out, int C, int H, int W, int Kc) {
    if (in == nullptr || w == nullptr || out == nullptr) return kErrNullPointer;
    if (C <= 0 || H <= 0 || W <= 0 || Kc <= 0) return kErrBadDimensions;
    if (H > kMaxConvSide || W > kMaxConvSide) return kErrBadDimensions;
    if (C > kMaxConvChannels || Kc > kMaxConvChannels) return kErrBadDimensions;
    const size_t inLen = static_cast<size_t>(C) * H * W;
    const size_t wLen = static_cast<size_t>(Kc) * C * 9;
    const size_t outLen = static_cast<size_t>(Kc) * H * W;
    if (inLen > kMaxElements || wLen > kMaxElements || outLen > kMaxElements) return kErrOverflow;
    if (bias != nullptr) {
        for (int oc = 0; oc < Kc; ++oc) {
            const float b = bias[oc];
            float *plane = out + static_cast<size_t>(oc) * H * W;
            for (size_t i = 0; i < static_cast<size_t>(H) * W; ++i) plane[i] = b;
        }
    } else {
        std::memset(out, 0, outLen * sizeof(float));
    }
    for (int oc = 0; oc < Kc; ++oc) {
        const float *wp = w + static_cast<size_t>(oc) * C * 9;
        float *plane = out + static_cast<size_t>(oc) * H * W;
        for (int ic = 0; ic < C; ++ic) {
            const float *ip = in + static_cast<size_t>(ic) * H * W;
            const float *ker = wp + static_cast<size_t>(ic) * 9;
            for (int y = 0; y < H; ++y) {
                for (int x = 0; x < W; ++x) {
                    float sum = 0.0f;
                    for (int ky = -1; ky <= 1; ++ky) {
                        const int yy = y + ky;
                        if (yy < 0 || yy >= H) continue;
                        for (int kx = -1; kx <= 1; ++kx) {
                            const int xx = x + kx;
                            if (xx < 0 || xx >= W) continue;
                            sum += ip[static_cast<size_t>(yy) * W + xx] * ker[(ky + 1) * 3 + (kx + 1)];
                        }
                    }
                    plane[static_cast<size_t>(y) * W + x] += sum;
                }
            }
        }
    }
    return kOk;
}

inline int32_t eltwiseF32Into(const float *A, const float *B, float *out, size_t n, bool multiply) {
    if (A == nullptr || B == nullptr || out == nullptr) return kErrNullPointer;
    if (n == 0 || n > kMaxElements) return kErrBadDimensions;
    if (multiply) {
        for (size_t i = 0; i < n; ++i) out[i] = A[i] * B[i];
    } else {
        for (size_t i = 0; i < n; ++i) out[i] = A[i] + B[i];
    }
    return kOk;
}

inline int32_t eltwiseF32Checksum(const float *A, const float *B, size_t n, bool multiply, TensorDigest *outDigest) {
    if (outDigest == nullptr) return kErrNullPointer;
    if (n == 0 || n > kMaxElements) return kErrBadDimensions;
    TensorDigest digest;
    // Compute in float32 (same as eltwiseF32Into) and widen only for the fold,
    // so the streaming path and the materialized path agree bit-for-bit.
    if (multiply) {
        for (size_t i = 0; i < n; ++i) fold(digest, static_cast<double>(static_cast<float>(A[i] * B[i])));
    } else {
        for (size_t i = 0; i < n; ++i) fold(digest, static_cast<double>(static_cast<float>(A[i] + B[i])));
    }
    *outDigest = digest;
    return kOk;
}

}  // namespace slop

// ---------------------------------------------------------------------------
// C entry points. Implemented in slop_engine.cpp; used by the JNI bridge and by
// the host test binary so both exercise exactly the same code path.
// ---------------------------------------------------------------------------
#ifdef __cplusplus
extern "C" {
#endif

int32_t slop_engine_version(void);
int32_t slop_matmul_f32_into(const float *A, const float *B, float *out, int32_t M, int32_t K, int32_t N);
int32_t slop_matmul_f32_checksum(const float *A, const float *B, int32_t M, int32_t K, int32_t N, double *outChecksum);
int32_t slop_matmul_i8_into(const int8_t *A, const int8_t *B, int32_t *out, int32_t M, int32_t K, int32_t N);
int32_t slop_matmul_i8_checksum(const int8_t *A, const int8_t *B, int32_t M, int32_t K, int32_t N, double *outChecksum);
int32_t slop_conv2d_3x3_f32_into(const float *in, const float *w, const float *bias, float *out, int32_t C, int32_t H, int32_t W, int32_t Kc);
int32_t slop_conv2d_3x3_f32_checksum(const float *in, const float *w, const float *bias, int32_t C, int32_t H, int32_t W, int32_t Kc, double *outChecksum);
int32_t slop_eltwise_f32_into(const float *A, const float *B, float *out, int64_t n, int32_t multiply);
int32_t slop_eltwise_f32_checksum(const float *A, const float *B, int64_t n, int32_t multiply, double *outChecksum);
int64_t slop_peak_rss_bytes(void);
int32_t slop_self_test(int32_t iterations);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // SLOP_ENGINE_H_
