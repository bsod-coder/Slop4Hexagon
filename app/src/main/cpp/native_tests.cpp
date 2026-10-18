// Host-side unit tests for the CPU micro-op engine.
//
// Built by tools/offline-native-check/run-native-checks.sh (plain g++/clang++,
// no Android NDK, no GoogleTest) and by `cmake -DSLOP_HOST_TESTS=ON`.
// Exits non-zero when any check fails so it can gate CI in restricted sandboxes.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "slop_engine.h"  // C entry points and kernels; slop_engine.cpp is linked

namespace {

int gChecks = 0;
int gFailures = 0;
std::vector<std::string> gFailedNames;

void check(bool condition, const std::string &name) {
    ++gChecks;
    if (condition) {
        std::printf("PASS  %s\n", name.c_str());
    } else {
        std::printf("FAIL  %s\n", name.c_str());
        ++gFailures;
        gFailedNames.push_back(name);
    }
}

struct Rng {
    uint64_t state;
    explicit Rng(uint64_t seed) : state(seed) {}
    uint64_t next() {
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        return state * 0x2545F4914F6CDD1DULL;
    }
    float uniform(float lo, float hi) {
        const double t = static_cast<double>(next() >> 11) / 9007199254740992.0;
        return static_cast<float>(lo + t * (hi - lo));
    }
    int8_t byte() { return static_cast<int8_t>((next() % 251u) - 125u); }
};

// Reference implementations written deliberately differently from the kernels.
std::vector<float> refMatmulF32(const std::vector<float> &A, const std::vector<float> &B, int M, int K, int N) {
    std::vector<float> C(static_cast<size_t>(M) * N, 0.0f);
    for (int i = 0; i < M; ++i)
        for (int j = 0; j < N; ++j) {
            double acc = 0.0;
            for (int k = 0; k < K; ++k) acc += static_cast<double>(A[static_cast<size_t>(i) * K + k]) * B[static_cast<size_t>(k) * N + j];
            C[static_cast<size_t>(i) * N + j] = static_cast<float>(acc);
        }
    return C;
}

std::vector<int32_t> refMatmulI8(const std::vector<int8_t> &A, const std::vector<int8_t> &B, int M, int K, int N) {
    std::vector<int32_t> C(static_cast<size_t>(M) * N, 0);
    for (int i = 0; i < M; ++i)
        for (int j = 0; j < N; ++j) {
            int32_t acc = 0;
            for (int k = 0; k < K; ++k) acc += static_cast<int32_t>(A[static_cast<size_t>(i) * K + k]) * B[static_cast<size_t>(k) * N + j];
            C[static_cast<size_t>(i) * N + j] = acc;
        }
    return C;
}

std::vector<float> refConv(const std::vector<float> &in, const std::vector<float> &w, const std::vector<float> &bias, int C, int H, int W, int Kc) {
    std::vector<float> out(static_cast<size_t>(Kc) * H * W, 0.0f);
    for (int oc = 0; oc < Kc; ++oc)
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                double acc = bias[oc];
                for (int ic = 0; ic < C; ++ic)
                    for (int ky = -1; ky <= 1; ++ky)
                        for (int kx = -1; kx <= 1; ++kx) {
                            const int yy = y + ky, xx = x + kx;
                            if (yy < 0 || yy >= H || xx < 0 || xx >= W) continue;
                            acc += static_cast<double>(in[static_cast<size_t>(ic) * H * W + yy * W + xx]) *
                                   w[((static_cast<size_t>(oc) * C + ic) * 9) + (ky + 1) * 3 + (kx + 1)];
                        }
                out[static_cast<size_t>(oc) * H * W + y * W + x] = static_cast<float>(acc);
            }
    return out;
}

double foldAll(const std::vector<float> &values) {
    slop::TensorDigest digest;
    for (float v : values) slop::fold(digest, v);
    return slop::checksumOf(digest);
}

double foldScale(const std::vector<float> &values) {
    slop::TensorDigest digest;
    for (float v : values) slop::fold(digest, v);
    return digest.absSum;
}

void testMatmulF32() {
    Rng rng(0xC0FFEEULL);
    const int shapes[][3] = {{1, 1, 1}, {1, 1, 4}, {3, 7, 2}, {8, 8, 8}, {16, 64, 16}, {64, 256, 64}};
    for (auto &s : shapes) {
        const int M = s[0], K = s[1], N = s[2];
        std::vector<float> A(static_cast<size_t>(M) * K), B(static_cast<size_t>(K) * N), C(static_cast<size_t>(M) * N, -1.0f);
        for (auto &v : A) v = rng.uniform(-3.0f, 3.0f);
        for (auto &v : B) v = rng.uniform(-3.0f, 3.0f);
        const int32_t rc = slop::matmulF32Into(A.data(), B.data(), C.data(), M, K, N);
        check(rc == slop::kOk, "matmul_f32 rc ok " + std::to_string(M) + "x" + std::to_string(K) + "x" + std::to_string(N));
        const auto ref = refMatmulF32(A, B, M, K, N);
        bool same = true;
        for (size_t i = 0; i < ref.size(); ++i)
            if (std::fabs(ref[i] - C[i]) > 1e-2f) same = false;
        check(same, "matmul_f32 matches reference " + std::to_string(M) + "x" + std::to_string(K) + "x" + std::to_string(N));

        double s1 = 0.0, s2 = 0.0;
        check(slop_matmul_f32_checksum(A.data(), B.data(), M, K, N, &s1) == slop::kOk, "matmul_f32 checksum rc ok");
        check(slop_matmul_f32_checksum(A.data(), B.data(), M, K, N, &s2) == slop::kOk, "matmul_f32 checksum rc ok (2nd)");
        check(s1 == s2, "matmul_f32 digest is deterministic");
        check(slop::digestsAgree(s1, foldAll(C), foldScale(C)), "matmul_f32 digest equals folded kernel output");
    }
}

void testMatmulInt8() {
    Rng rng(0xBEEF01ULL);
    const int shapes[][3] = {{1, 1, 1}, {5, 17, 3}, {64, 256, 64}};
    for (auto &s : shapes) {
        const int M = s[0], K = s[1], N = s[2];
        std::vector<int8_t> A(static_cast<size_t>(M) * K), B(static_cast<size_t>(K) * N);
        std::vector<int32_t> C(static_cast<size_t>(M) * N, 12345);
        for (auto &v : A) v = rng.byte();
        for (auto &v : B) v = rng.byte();
        check(slop::matmulI8Into(A.data(), B.data(), C.data(), M, K, N) == slop::kOk, "matmul_i8 rc ok");
        const auto ref = refMatmulI8(A, B, M, K, N);
        check(ref == C, "matmul_i8 matches reference " + std::to_string(M) + "x" + std::to_string(K) + "x" + std::to_string(N));
        double sum = 0.0;
        check(slop_matmul_i8_checksum(A.data(), B.data(), M, K, N, &sum) == slop::kOk, "matmul_i8 digest rc ok");
        slop::TensorDigest refDigest;
        for (int32_t v : ref) slop::fold(refDigest, static_cast<double>(v));
        check(sum == slop::checksumOf(refDigest), "matmul_i8 digest equals folded reference (integer math is exact)");
    }
    // Accumulation must not overflow at the largest allowed inner dimension.
    const int M = 2, K = slop::kMaxInt8InnerDim, N = 2;
    std::vector<int8_t> A(static_cast<size_t>(M) * K, -128), B(static_cast<size_t>(K) * N, -128);
    std::vector<int32_t> C(static_cast<size_t>(M) * N, 0);
    // Note: -128 * -128 * 8192 = 134217728 fits in int32.
    check(slop::matmulI8Into(A.data(), B.data(), C.data(), M, K, N) == slop::kOk, "matmul_i8 max inner dim accepted");
    check(C[0] == 134217728, "matmul_i8 max inner dim value exact");
    check(slop::matmulI8Into(A.data(), B.data(), C.data(), M, K + 1, N) == slop::kErrBadDimensions, "matmul_i8 rejects inner dim above bound");
}

void testConv2d() {
    Rng rng(0x5EEDULL);
    const int cases[][4] = {{1, 1, 1, 1}, {3, 5, 7, 2}, {4, 8, 8, 8}};
    for (auto &cs : cases) {
        const int C = cs[0], H = cs[1], W = cs[2], Kc = cs[3];
        std::vector<float> in(static_cast<size_t>(C) * H * W), w(static_cast<size_t>(Kc) * C * 9), bias(Kc), out(static_cast<size_t>(Kc) * H * W, -7.0f);
        for (auto &v : in) v = rng.uniform(-1.5f, 1.5f);
        for (auto &v : w) v = rng.uniform(-1.0f, 1.0f);
        for (auto &v : bias) v = rng.uniform(-0.25f, 0.25f);
        check(slop::conv2d3x3F32Into(in.data(), w.data(), bias.data(), out.data(), C, H, W, Kc) == slop::kOk, "conv2d rc ok");
        const auto ref = refConv(in, w, bias, C, H, W, Kc);
        bool same = true;
        for (size_t i = 0; i < ref.size(); ++i)
            if (std::fabs(ref[i] - out[i]) > 1e-2f) same = false;
        check(same, "conv2d matches reference " + std::to_string(C) + "x" + std::to_string(H) + "x" + std::to_string(W));
        double sum = 0.0;
        check(slop_conv2d_3x3_f32_checksum(in.data(), w.data(), bias.data(), C, H, W, Kc, &sum) == slop::kOk, "conv2d digest rc ok");
        // Streaming path uses a different summation order than the kernel, so it
        // must agree within the documented relative tolerance, not exactly.
        check(slop::digestsAgree(sum, foldAll(ref), foldScale(ref)), "conv2d digest agrees with folded reference within tolerance");
        // Null bias is legal and means zero bias.
        std::vector<float> out2(out.size(), -7.0f);
        std::vector<float> zeroBias(Kc, 0.0f);
        check(slop::conv2d3x3F32Into(in.data(), w.data(), nullptr, out2.data(), C, H, W, Kc) == slop::kOk, "conv2d accepts null bias");
        const auto refNoBias = refConv(in, w, zeroBias, C, H, W, Kc);
        bool sameNoBias = true;
        for (size_t i = 0; i < refNoBias.size(); ++i)
            if (std::fabs(refNoBias[i] - out2[i]) > 1e-2f) sameNoBias = false;
        check(sameNoBias, "conv2d null bias equals zero bias");
    }
}

void testEltwise() {
    Rng rng(0xFEEDULL);
    for (int n : {1, 7, 1024}) {
        std::vector<float> A(n), B(n), out(n, 0.0f);
        for (auto &v : A) v = rng.uniform(-4.0f, 4.0f);
        for (auto &v : B) v = rng.uniform(-4.0f, 4.0f);
        check(slop::eltwiseF32Into(A.data(), B.data(), out.data(), n, false) == slop::kOk, "add rc ok");
        bool ok = true;
        for (int i = 0; i < n; ++i) ok = ok && std::fabs(out[i] - (A[i] + B[i])) < 1e-5f;
        check(ok, "add matches reference");
        check(slop::eltwiseF32Into(A.data(), B.data(), out.data(), n, true) == slop::kOk, "mul rc ok");
        ok = true;
        for (int i = 0; i < n; ++i) ok = ok && std::fabs(out[i] - (A[i] * B[i])) < 1e-4f;
        check(ok, "mul matches reference");
        double sum = 0.0;
        check(slop_eltwise_f32_checksum(A.data(), B.data(), n, 0, &sum) == slop::kOk, "add digest rc ok");
        std::vector<float> expected(n);
        for (int i = 0; i < n; ++i) expected[i] = A[i] + B[i];
        check(sum == foldAll(expected), "add digest equals folded reference");
    }
}

void testValidation() {
    std::vector<float> small(4, 1.0f), out(4, 0.0f);
    check(slop::matmulF32Into(nullptr, small.data(), out.data(), 1, 2, 2) == slop::kErrNullPointer, "matmul rejects null A");
    check(slop::matmulF32Into(small.data(), nullptr, out.data(), 1, 2, 2) == slop::kErrNullPointer, "matmul rejects null B");
    check(slop::matmulF32Into(small.data(), small.data(), nullptr, 1, 2, 2) == slop::kErrNullPointer, "matmul rejects null out");
    check(slop::matmulF32Into(small.data(), small.data(), out.data(), 0, 2, 2) == slop::kErrBadDimensions, "matmul rejects zero dim");
    check(slop::matmulF32Into(small.data(), small.data(), out.data(), -3, 2, 2) == slop::kErrBadDimensions, "matmul rejects negative dim");
    check(slop::matmulF32Into(small.data(), small.data(), out.data(), slop::kMaxMatrixDim + 1, 1, 1) == slop::kErrBadDimensions, "matmul rejects oversized dim");
    check(slop::matmulF32Into(small.data(), small.data(), out.data(), slop::kMaxMatrixDim, slop::kMaxMatrixDim, slop::kMaxMatrixDim) == slop::kErrOverflow, "matmul rejects element budget overflow");
    {
        slop::TensorDigest digest;
        check(slop::matmulF32Checksum(small.data(), small.data(), slop::kMaxMatrixDim, slop::kMaxMatrixDim, slop::kMaxMatrixDim, &digest) == slop::kErrOverflow, "matmul digest rejects element budget overflow");
    }
    check(slop::matmulF32Checksum(small.data(), small.data(), 1, 2, 2, nullptr) == slop::kErrNullPointer, "matmul digest rejects null output");
    double s = 0.0;
    check(slop_matmul_f32_checksum(small.data(), small.data(), 1, 2, 2, &s) == slop::kOk, "checksum path shares validation");
    check(slop::conv2d3x3F32Into(nullptr, small.data(), nullptr, out.data(), 1, 1, 1, 1) == slop::kErrNullPointer, "conv rejects null input");
    check(slop::conv2d3x3F32Into(small.data(), small.data(), nullptr, out.data(), 0, 1, 1, 1) == slop::kErrBadDimensions, "conv rejects zero channels");
    check(slop::conv2d3x3F32Into(small.data(), small.data(), nullptr, out.data(), 1, slop::kMaxConvSide + 1, 1, 1) == slop::kErrBadDimensions, "conv rejects oversized side");
    check(slop::eltwiseF32Into(nullptr, small.data(), out.data(), 4, false) == slop::kErrNullPointer, "eltwise rejects null");
    check(slop::eltwiseF32Into(small.data(), small.data(), out.data(), 0, false) == slop::kErrBadDimensions, "eltwise rejects zero length");
    check(slop::eltwiseF32Into(small.data(), small.data(), out.data(), slop::kMaxElements + 1, false) == slop::kErrBadDimensions, "eltwise rejects oversized length");
    check(slop_eltwise_f32_checksum(small.data(), small.data(), -1, 0, &s) == slop::kErrBadDimensions, "eltwise checksum rejects negative length");
}

void testEngineSelfTestAndMemory() {
    check(slop_engine_version() == 1, "engine version is reported");
    check(slop_self_test(3) == 0, "engine self-test passes (kernels vs naive reference)");
    check(slop_self_test(0) == 0, "engine self-test clamps zero iterations");
    const int64_t rss = slop_peak_rss_bytes();
    check(rss > 0, "peak RSS probe returns a positive value");

    // A large allocation must not be possible beyond the element budget:
    // guard is what protects the app from OOM-kill on a mid-range phone.
    const size_t huge = static_cast<size_t>(slop::kMaxElements);
    std::vector<float> big(huge, 0.0f), bigB(huge, 0.0f), bigOut(huge, 0.0f);
    check(slop::eltwiseF32Into(big.data(), bigB.data(), bigOut.data(), huge, false) == slop::kOk, "eltwise accepts the documented maximum size");
}

}  // namespace

int main() {
    testMatmulF32();
    testMatmulInt8();
    testConv2d();
    testEltwise();
    testValidation();
    testEngineSelfTestAndMemory();

    std::printf("\nResult: %d checks, %d failed\n", gChecks, gFailures);
    if (gFailures > 0) {
        for (const auto &name : gFailedNames) std::printf("  failed: %s\n", name.c_str());
        return 1;
    }
    return 0;
}
