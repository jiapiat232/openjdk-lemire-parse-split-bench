/**
 * 剥离 JVM / JEP 454 FFM 跨界开销后的纯 C++20 原生算法计时基准
 * 支持同时在 Apple Silicon (arm64) 与 QEMU RISC-V 64 (riscv64) 下编译运行。
 */
#include "include/fast_float.h"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

extern "C" {
double lemire_parse_double(const char* str, size_t len);
float lemire_parse_float(const char* str, size_t len);
size_t lemire_split_offsets_neon(const char* data, size_t len, char delimiter, int32_t* out_offsets);
size_t lemire_split_offsets_cxx20_views(const char* data, size_t len, char delimiter, int32_t* out_offsets);
size_t lemire_split_parse_cxx20_views(const char* data, size_t len, char delimiter, double* out);
size_t lemire_split_parse_neon_simd(const char* data, size_t len, char delimiter, double* out);
}

int main() {
    constexpr int NUM_COUNT = 256;
    std::mt19937_64 rng(42);
    std::uniform_real_distribution<double> udist(-0.5, 0.5);
    std::uniform_int_distribution<int> edist(-150, 150);

    std::vector<std::string> num_strs(NUM_COUNT);
    std::string csv_line;
    for (int i = 0; i < NUM_COUNT; i++) {
        double raw = udist(rng) * std::pow(10.0, edist(rng));
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%.17g", raw);
        num_strs[i] = buf;
        if (i > 0) csv_line.push_back(',');
        csv_line.append(num_strs[i]);
    }

#if defined(__riscv)
    constexpr int ITERS = 2000;
    const char* arch_name = "RISC-V 64 (RV64GCV + Zba/Zbb/Zbs)";
#else
    constexpr int ITERS = 20000;
    const char* arch_name = "Apple Silicon ARM64 (NEON)";
#endif

    volatile double sink_d = 0.0;
    volatile float sink_f = 0.0f;
    volatile size_t sink_sz = 0;

    // 1. Lemire fast_float<double>
    auto t0 = std::chrono::high_resolution_clock::now();
    for (int it = 0; it < ITERS; it++) {
        double sum = 0.0;
        for (int i = 0; i < NUM_COUNT; i++) {
            double v = 0.0;
            fast_float::from_chars(num_strs[i].data(), num_strs[i].data() + num_strs[i].size(), v);
            sum += v;
        }
        sink_d = sum;
    }
    auto t1 = std::chrono::high_resolution_clock::now();
    double fastfloat_d_ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / (ITERS * NUM_COUNT);

    // 2. Lemire fast_float<float>
    t0 = std::chrono::high_resolution_clock::now();
    for (int it = 0; it < ITERS; it++) {
        float sum = 0.0f;
        for (int i = 0; i < NUM_COUNT; i++) {
            float v = 0.0f;
            fast_float::from_chars(num_strs[i].data(), num_strs[i].data() + num_strs[i].size(), v);
            sum += v;
        }
        sink_f = sum;
    }
    t1 = std::chrono::high_resolution_clock::now();
    double fastfloat_f_ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / (ITERS * NUM_COUNT);

    // 3. 纯切分 256 个字段 (C++20 std::views::split vs SIMD Bitmask)
    std::vector<int32_t> offsets(NUM_COUNT + 16);
    t0 = std::chrono::high_resolution_clock::now();
    for (int it = 0; it < ITERS; it++) {
        sink_sz = lemire_split_offsets_cxx20_views(csv_line.data(), csv_line.size(), ',', offsets.data());
    }
    t1 = std::chrono::high_resolution_clock::now();
    double views_split_ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / ITERS;

    t0 = std::chrono::high_resolution_clock::now();
    for (int it = 0; it < ITERS; it++) {
        sink_sz = lemire_split_offsets_neon(csv_line.data(), csv_line.size(), ',', offsets.data());
    }
    t1 = std::chrono::high_resolution_clock::now();
    double simd_split_ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / ITERS;

    // 4. 切分 + 解析 256 个 double 完整流水线
    std::vector<double> out_doubles(NUM_COUNT + 16);
    t0 = std::chrono::high_resolution_clock::now();
    for (int it = 0; it < ITERS; it++) {
        size_t n = lemire_split_parse_cxx20_views(csv_line.data(), csv_line.size(), ',', out_doubles.data());
        sink_d = out_doubles[n - 1];
    }
    t1 = std::chrono::high_resolution_clock::now();
    double views_split_parse_ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / ITERS;

    t0 = std::chrono::high_resolution_clock::now();
    for (int it = 0; it < ITERS; it++) {
        size_t n = lemire_split_parse_neon_simd(csv_line.data(), csv_line.size(), ',', out_doubles.data());
        sink_d = out_doubles[n - 1];
    }
    t1 = std::chrono::high_resolution_clock::now();
    double simd_split_parse_ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / ITERS;

    std::printf("=== Pure Native C++20 Lemire Benchmark [%s] ===\n", arch_name);
    std::printf("1. fast_float<double>              : %8.2f ns/op\n", fastfloat_d_ns);
    std::printf("2. fast_float<float>               : %8.2f ns/op\n", fastfloat_f_ns);
    std::printf("3. split (256) C++20 views::split  : %8.2f ns/op\n", views_split_ns);
    std::printf("4. split (256) 64B SIMD Bitmask    : %8.2f ns/op\n", simd_split_ns);
    std::printf("5. split+parse C++20 views         : %8.2f ns/op (%6.2f ns/num)\n",
                views_split_parse_ns, views_split_parse_ns / NUM_COUNT);
    std::printf("6. split+parse 64B SIMD Bitmask    : %8.2f ns/op (%6.2f ns/num)\n",
                simd_split_parse_ns, simd_split_parse_ns / NUM_COUNT);
    return 0;
}
