// C entry points over the header-only kernels, plus a self-test used by the
// app to earn "verified execution" for the CPU backend, and a peak-RSS probe.
//
// This file is dependency-free and compiles for both Android (NDK clang) and
// the host toolchain (used by tools/offline-native-check).
#include "slop_engine.h"

#include <sys/resource.h>

#include <algorithm>
#include <vector>

namespace {

using slop::fold;
using slop::TensorDigest;

// Naive reference implementations (never used in production paths) so the
// optimized kernels can be validated against an obviously-correct formulation.
float naiveMatmul32(const float *A, const float *B, int K, int N, int i, int j) {
    double acc = 0.0;
    for (int k = 0; k < K; ++k) acc += static_cast<double>(A[static_cast<size_t>(i) * K + k]) * B[static_cast<size_t>(k) * N + j];
    return static_cast<float>(acc);
}

int32_t convChecksum(const float *in, const float *w, const float *bias, int C, int H, int W, int Kc, TensorDigest *outDigest) {
    if (in == nullptr || w == nullptr || outDigest == nullptr) return slop::kErrNullPointer;
    if (C <= 0 || H <= 0 || W <= 0 || Kc <= 0) return slop::kErrBadDimensions;
    if (H > slop::kMaxConvSide || W > slop::kMaxConvSide) return slop::kErrBadDimensions;
    if (C > slop::kMaxConvChannels || Kc > slop::kMaxConvChannels) return slop::kErrBadDimensions;
    const size_t inLen = static_cast<size_t>(C) * H * W;
    const size_t wLen = static_cast<size_t>(Kc) * C * 9;
    if (inLen > slop::kMaxElements || wLen > slop::kMaxElements) return slop::kErrOverflow;
    TensorDigest digest;
    for (int oc = 0; oc < Kc; ++oc) {
        const float *wp = w + static_cast<size_t>(oc) * C * 9;
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                float sum = bias != nullptr ? bias[oc] : 0.0f;
                for (int ic = 0; ic < C; ++ic) {
                    const float *ip = in + static_cast<size_t>(ic) * H * W;
                    const float *ker = wp + static_cast<size_t>(ic) * 9;
                    for (int ky = -1; ky <= 1; ++ky) {
                        const int yy = y + ky;
                        if (yy < 0 || yy >= H) continue;
                        for (int kx = -1; kx <= 1; ++kx) {
                            const int xx = x + kx;
                            if (xx < 0 || xx >= W) continue;
                            sum += ip[static_cast<size_t>(yy) * W + xx] * ker[(ky + 1) * 3 + (kx + 1)];
                        }
                    }
                }
                fold(digest, sum);
            }
        }
    }
    *outDigest = digest;
    return slop::kOk;
}

// Deterministic pseudo-random generator (xorshift64*) so self-test data does
// not depend on any platform rand() implementation.
struct Xorshift {
    uint64_t state;
    explicit Xorshift(uint64_t seed) : state(seed == 0 ? 0x9E3779B97F4A7C15ULL : seed) {}
    uint64_t next() {
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        return state * 0x2545F4914F6CDD1DULL;
    }
    float uniform(float lo, float hi) {
        const uint64_t v = next();
        const double t = static_cast<double>(v >> 11) / 9007199254740992.0;  // [0,1)
        return static_cast<float>(lo + t * (hi - lo));
    }
};

}  // namespace

extern "C" {

int32_t slop_engine_version(void) { return 1; }

int32_t slop_matmul_f32_into(const float *A, const float *B, float *out, int32_t M, int32_t K, int32_t N) {
    return slop::matmulF32Into(A, B, out, M, K, N);
}

int32_t slop_matmul_f32_checksum(const float *A, const float *B, int32_t M, int32_t K, int32_t N, double *outChecksum) {
    if (outChecksum == nullptr) return slop::kErrNullPointer;
    TensorDigest digest;
    const int32_t rc = slop::matmulF32Checksum(A, B, M, K, N, &digest);
    *outChecksum = slop::checksumOf(digest);
    return rc;
}

int32_t slop_matmul_i8_into(const int8_t *A, const int8_t *B, int32_t *out, int32_t M, int32_t K, int32_t N) {
    return slop::matmulI8Into(A, B, out, M, K, N);
}

int32_t slop_matmul_i8_checksum(const int8_t *A, const int8_t *B, int32_t M, int32_t K, int32_t N, double *outChecksum) {
    if (outChecksum == nullptr) return slop::kErrNullPointer;
    TensorDigest digest;
    const int32_t rc = slop::matmulI8Checksum(A, B, M, K, N, &digest);
    *outChecksum = slop::checksumOf(digest);
    return rc;
}

int32_t slop_conv2d_3x3_f32_into(const float *in, const float *w, const float *bias, float *out, int32_t C, int32_t H, int32_t W, int32_t Kc) {
    return slop::conv2d3x3F32Into(in, w, bias, out, C, H, W, Kc);
}

int32_t slop_conv2d_3x3_f32_checksum(const float *in, const float *w, const float *bias, int32_t C, int32_t H, int32_t W, int32_t Kc, double *outChecksum) {
    if (outChecksum == nullptr) return slop::kErrNullPointer;
    TensorDigest digest;
    const int32_t rc = convChecksum(in, w, bias, C, H, W, Kc, &digest);
    *outChecksum = slop::checksumOf(digest);
    return rc;
}

int32_t slop_eltwise_f32_into(const float *A, const float *B, float *out, int64_t n, int32_t multiply) {
    if (n <= 0 || static_cast<uint64_t>(n) > slop::kMaxElements) return slop::kErrBadDimensions;
    return slop::eltwiseF32Into(A, B, out, static_cast<size_t>(n), multiply != 0);
}

int32_t slop_eltwise_f32_checksum(const float *A, const float *B, int64_t n, int32_t multiply, double *outChecksum) {
    if (outChecksum == nullptr) return slop::kErrNullPointer;
    if (n <= 0 || static_cast<uint64_t>(n) > slop::kMaxElements) return slop::kErrBadDimensions;
    TensorDigest digest;
    const int32_t rc = slop::eltwiseF32Checksum(A, B, static_cast<size_t>(n), multiply != 0, &digest);
    *outChecksum = slop::checksumOf(digest);
    return rc;
}

int64_t slop_peak_rss_bytes(void) {
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) != 0) return -1;
#if defined(__APPLE__)
    return static_cast<int64_t>(usage.ru_maxrss);  // bytes on macOS
#else
    return static_cast<int64_t>(usage.ru_maxrss) * 1024LL;  // kilobytes on Linux/Android
#endif
}

// Runs each kernel against a naive reference on deterministic data, and checks
// that repeated execution yields identical checksums. Returns 0 when every
// check passes, otherwise the number of failed checks. The app only promotes
// the CPU backend to VERIFIED_EXECUTION after a clean run.
int32_t slop_self_test(int32_t iterations) {
    int32_t failures = 0;
    if (iterations < 1) iterations = 1;
    if (iterations > 64) iterations = 64;

    for (int32_t it = 0; it < iterations; ++it) {
        Xorshift rng(0x1234567u + static_cast<uint32_t>(it) * 7919u);

        // --- float32 matmul vs naive reference -----------------------------
        const int M = 7, K = 11, N = 5;
        std::vector<float> A(static_cast<size_t>(M) * K), B(static_cast<size_t>(K) * N), C(static_cast<size_t>(M) * N);
        for (auto &v : A) v = rng.uniform(-2.0f, 2.0f);
        for (auto &v : B) v = rng.uniform(-2.0f, 2.0f);
        if (slop::matmulF32Into(A.data(), B.data(), C.data(), M, K, N) != slop::kOk) ++failures;
        for (int i = 0; i < M; ++i) {
            for (int j = 0; j < N; ++j) {
                const float ref = naiveMatmul32(A.data(), B.data(), K, N, i, j);
                if (std::fabs(ref - C[static_cast<size_t>(i) * N + j]) > 1e-3f) ++failures;
            }
        }
        double s1 = 0.0, s2 = 0.0;
        if (slop_matmul_f32_checksum(A.data(), B.data(), M, K, N, &s1) != slop::kOk) ++failures;
        if (slop_matmul_f32_checksum(A.data(), B.data(), M, K, N, &s2) != slop::kOk) ++failures;
        if (s1 != s2) ++failures;  // determinism of one implementation
        // The streaming digest must agree with folding the materialized output.
        TensorDigest refDigest;
        for (float v : C) fold(refDigest, v);
        if (!slop::digestsAgree(s1, slop::checksumOf(refDigest), refDigest.absSum)) ++failures;

        // --- int8 matmul vs reference ---------------------------------------
        const int Mi = 4, Ki = 8, Ni = 3;
        std::vector<int8_t> Ai(static_cast<size_t>(Mi) * Ki), Bi(static_cast<size_t>(Ki) * Ni);
        std::vector<int32_t> Ci(static_cast<size_t>(Mi) * Ni);
        for (auto &v : Ai) v = static_cast<int8_t>((rng.next() % 251u) - 125u);
        for (auto &v : Bi) v = static_cast<int8_t>((rng.next() % 251u) - 125u);
        if (slop::matmulI8Into(Ai.data(), Bi.data(), Ci.data(), Mi, Ki, Ni) != slop::kOk) ++failures;
        for (int i = 0; i < Mi; ++i) {
            for (int j = 0; j < Ni; ++j) {
                int32_t ref = 0;
                for (int k = 0; k < Ki; ++k) ref += static_cast<int32_t>(Ai[static_cast<size_t>(i) * Ki + k]) * Bi[static_cast<size_t>(k) * Ni + j];
                if (ref != Ci[static_cast<size_t>(i) * Ni + j]) ++failures;
            }
        }

        // --- 3x3 conv vs reference ------------------------------------------
        const int Cc = 3, Hh = 6, Ww = 5, Kk = 2;
        std::vector<float> img(static_cast<size_t>(Cc) * Hh * Ww), wt(static_cast<size_t>(Kk) * Cc * 9), bias(Kk), out(static_cast<size_t>(Kk) * Hh * Ww);
        for (auto &v : img) v = rng.uniform(-1.0f, 1.0f);
        for (auto &v : wt) v = rng.uniform(-1.0f, 1.0f);
        for (auto &v : bias) v = rng.uniform(-0.5f, 0.5f);
        if (slop::conv2d3x3F32Into(img.data(), wt.data(), bias.data(), out.data(), Cc, Hh, Ww, Kk) != slop::kOk) ++failures;
        for (int oc = 0; oc < Kk; ++oc) {
            for (int y = 0; y < Hh; ++y) {
                for (int x = 0; x < Ww; ++x) {
                    double ref = bias[oc];
                    for (int ic = 0; ic < Cc; ++ic) {
                        for (int ky = -1; ky <= 1; ++ky) {
                            for (int kx = -1; kx <= 1; ++kx) {
                                const int yy = y + ky, xx = x + kx;
                                if (yy < 0 || yy >= Hh || xx < 0 || xx >= Ww) continue;
                                ref += static_cast<double>(img[static_cast<size_t>(ic) * Hh * Ww + yy * Ww + xx]) *
                                       wt[((static_cast<size_t>(oc) * Cc + ic) * 9) + (ky + 1) * 3 + (kx + 1)];
                            }
                        }
                    }
                    if (std::fabs(ref - out[static_cast<size_t>(oc) * Hh * Ww + y * Ww + x]) > 1e-3) ++failures;
                }
            }
        }
        // Conv checksum path must agree with materialized output folded the
        // same way.
        double convSum = 0.0;
        if (slop_conv2d_3x3_f32_checksum(img.data(), wt.data(), bias.data(), Cc, Hh, Ww, Kk, &convSum) != slop::kOk) ++failures;
        TensorDigest convRefDigest;
        double refAbs = 0.0;
        for (int oc = 0; oc < Kk; ++oc)
            for (int y = 0; y < Hh; ++y)
                for (int x = 0; x < Ww; ++x) {
                    const float v = out[static_cast<size_t>(oc) * Hh * Ww + y * Ww + x];
                    fold(convRefDigest, v);
                    refAbs += std::fabs(v);
                }
        // Different summation order => compare with relative tolerance.
        if (!slop::digestsAgree(convSum, slop::checksumOf(convRefDigest), refAbs)) ++failures;

        // --- element-wise ----------------------------------------------------
        const size_t nEl = 64;
        std::vector<float> X(nEl), Y(nEl), Z(nEl);
        for (auto &v : X) v = rng.uniform(-3.0f, 3.0f);
        for (auto &v : Y) v = rng.uniform(-3.0f, 3.0f);
        if (slop::eltwiseF32Into(X.data(), Y.data(), Z.data(), nEl, false) != slop::kOk) ++failures;
        for (size_t i = 0; i < nEl; ++i)
            if (std::fabs(Z[i] - (X[i] + Y[i])) > 1e-5f) ++failures;
        if (slop::eltwiseF32Into(X.data(), Y.data(), Z.data(), nEl, true) != slop::kOk) ++failures;
        for (size_t i = 0; i < nEl; ++i)
            if (std::fabs(Z[i] - (X[i] * Y[i])) > 1e-5f) ++failures;

        // --- validation: bad inputs must return error codes, never crash ------
        if (slop::matmulF32Into(nullptr, B.data(), C.data(), M, K, N) != slop::kErrNullPointer) ++failures;
        if (slop::matmulF32Into(A.data(), B.data(), C.data(), 0, K, N) != slop::kErrBadDimensions) ++failures;
        if (slop::matmulF32Into(A.data(), B.data(), C.data(), -1, K, N) != slop::kErrBadDimensions) ++failures;
        if (slop::matmulI8Into(Ai.data(), Bi.data(), Ci.data(), Mi, slop::kMaxInt8InnerDim + 1, Ni) != slop::kErrBadDimensions) ++failures;
        if (slop::matmulF32Into(A.data(), B.data(), C.data(), slop::kMaxMatrixDim, slop::kMaxMatrixDim, slop::kMaxMatrixDim) != slop::kErrOverflow) ++failures;
        {
            TensorDigest overflowDigest;
            if (slop::matmulF32Checksum(A.data(), B.data(), slop::kMaxMatrixDim, slop::kMaxMatrixDim, slop::kMaxMatrixDim, &overflowDigest) != slop::kErrOverflow) ++failures;
        }
        if (slop::conv2d3x3F32Into(img.data(), wt.data(), nullptr, out.data(), Cc, slop::kMaxConvSide + 1, Ww, Kk) != slop::kErrBadDimensions) ++failures;
    }

    return failures;
}

}  // extern "C"
