/**
 * ============================================================================
 * Daniel Lemire 算法 ARM64 (Apple Silicon NEON) Native 实现 (供 JEP 454 FFM 调用)
 * ============================================================================
 *
 * 【代码上游来源溯源 (Upstream Provenance)】
 * 本文件中的算法来自 Daniel Lemire 教授的以下 3 个独立上游仓库（均已 Clone 至 ../upstream/）：
 *
 * 1. fast_float (Double.parseDouble / Float.parseFloat 底层算法):
 *    - 仓库: https://github.com/fastfloat/fast_float
 *    - 本地路径: ../../upstream/fast_float (单头文件位于 ../include/fast_float.h)
 *    - 核心原理: Eisel-Lemire 128-bit 尾数乘法 (ARM64 `umulh` 指令) + 8位数字 SWAR 块解析，
 *               严格遵循 IEEE 754 Round-to-Even (0 ULP 误差)。
 *
 * 2. C++20 std::views::split 零拷贝字符串切分与解析:
 *    - 仓库: https://github.com/lemire/Code-used-on-Daniel-Lemire-s-blog
 *    - 本地路径:
 *      * ../../upstream/Code-used-on-Daniel-Lemire-s-blog/2024/12/21/include/csvparser.h
 *      * ../../upstream/Code-used-on-Daniel-Lemire-s-blog/2025/05/10/csv.cpp
 *
 * 3. ARM64 NEON 64字节比较生成 64-bit 位掩码 (Bitmask) + ctz 无分支切分:
 *    - 仓库 1: https://github.com/simdjson/simdjson
 *      * 本地路径: ../../upstream/simdjson/include/simdjson/arm64/simd.h (第 464-492 行 `simd8x64::eq` 与 `to_bitmask`)
 *    - 仓库 2: https://github.com/lemire/Code-used-on-Daniel-Lemire-s-blog
 *      * 本地路径: ../../upstream/Code-used-on-Daniel-Lemire-s-blog/2023/08/09/src/name_to_dnswire.c (第 751-764 行 `_tzcnt_u64` + `mask &= mask - 1` 分隔符迭代)
 * ============================================================================
 */

#include "../include/fast_float.h"
#include <arm_neon.h>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <ranges>
#include <string_view>

extern "C" {

// 1. 单纯调用 fast_float::from_chars<double> 解析双精度浮点数
double lemire_parse_double(const char* str, size_t len) {
    double result = std::numeric_limits<double>::quiet_NaN();
    fast_float::from_chars(str, str + len, result);
    return result;
}

// 2. 单纯调用 fast_float::from_chars<float> 解析单精度浮点数
float lemire_parse_float(const char* str, size_t len) {
    float result = std::numeric_limits<float>::quiet_NaN();
    fast_float::from_chars(str, str + len, result);
    return result;
}

// 3. Lemire C++20 std::views::split + std::string_view + fast_float::from_chars
// 切分字符串并解析每个子串为 double，写入调用方传入的 out 数组，返回解析出的 double 个数
size_t lemire_split_parse_cxx20_views(const char* data, size_t len, char delimiter, double* out) {
    std::string_view sv(data, len);
    size_t count = 0;
    for (auto&& part : sv | std::views::split(delimiter)) {
        const char* first = &*part.begin();
        const char* last = first + std::ranges::distance(part);
        double val = 0.0;
        fast_float::from_chars(first, last, val);
        out[count++] = val;
    }
    return count;
}

// 4. 源自 simdjson/include/simdjson/arm64/simd.h (lines 464-482) 的 ARM64 NEON 64字节位掩码压缩函数：
// 将 64 字节比较结果 (4 x uint8x16_t，匹配字节为 0xFF，非匹配为 0x00)
// 压缩为一个 64-bit uint64_t 位掩码（第 k 位为 1 表示第 k 字节命中分隔符）
static inline uint64_t neon_movemask_64bytes(uint8x16_t p0, uint8x16_t p1, uint8x16_t p2, uint8x16_t p3) {
    const uint8x16_t bit_mask = {
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80,
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80
    };
    uint8x16_t t0 = vandq_u8(p0, bit_mask);
    uint8x16_t t1 = vandq_u8(p1, bit_mask);
    uint8x16_t t2 = vandq_u8(p2, bit_mask);
    uint8x16_t t3 = vandq_u8(p3, bit_mask);

    // 两两相邻字节水平求和归约 (vpaddq_u8)：64 字节 -> 32 字节 -> 16 字节 -> 8 字节 (uint64_t)
    uint8x16_t sum0 = vpaddq_u8(t0, t1);
    uint8x16_t sum1 = vpaddq_u8(t2, t3);
    sum0 = vpaddq_u8(sum0, sum1);
    sum0 = vpaddq_u8(sum0, sum0);
    return vgetq_lane_u64(vreinterpretq_u64_u8(sum0), 0);
}

// 5. 纯字符串切分：使用 ARM64 NEON 找出所有分隔符偏移量，写入 out_offsets 数组，返回子串数量
size_t lemire_split_offsets_neon(const char* data, size_t len, char delimiter, int32_t* out_offsets) {
    const uint8x16_t delim_vec = vdupq_n_u8(static_cast<uint8_t>(delimiter));
    size_t idx = 0;
    size_t count = 0;

    while (idx + 64 <= len) {
        const uint8_t* ptr = reinterpret_cast<const uint8_t*>(data + idx);
        uint8x16_t v0 = vld1q_u8(ptr);
        uint8x16_t v1 = vld1q_u8(ptr + 16);
        uint8x16_t v2 = vld1q_u8(ptr + 32);
        uint8x16_t v3 = vld1q_u8(ptr + 48);

        uint64_t mask = neon_movemask_64bytes(
            vceqq_u8(v0, delim_vec),
            vceqq_u8(v1, delim_vec),
            vceqq_u8(v2, delim_vec),
            vceqq_u8(v3, delim_vec)
        );

        while (mask != 0) {
            unsigned bit = __builtin_ctzll(mask);
            out_offsets[count++] = static_cast<int32_t>(idx + bit);
            mask &= (mask - 1);
        }
        idx += 64;
    }

    // 处理尾部不足 64 字节的剩余部分
    while (idx < len) {
        if (data[idx] == delimiter) {
            out_offsets[count++] = static_cast<int32_t>(idx);
        }
        idx++;
    }
    out_offsets[count++] = static_cast<int32_t>(len);
    return count;
}

// 5b. 纯字符串切分：使用 Lemire C++20 std::views::split 找出所有分隔符偏移量
size_t lemire_split_offsets_cxx20_views(const char* data, size_t len, char delimiter, int32_t* out_offsets) {
    std::string_view sv(data, len);
    size_t count = 0;
    for (auto&& part : sv | std::views::split(delimiter)) {
        const char* last = &*part.begin() + std::ranges::distance(part);
        out_offsets[count++] = static_cast<int32_t>(last - data);
    }
    // 若末尾字符恰好是分隔符，C++20 views::split 不会为末尾空串再迭代一次，补齐末尾偏移量以对齐 String.split
    if (len == 0 || data[len - 1] == delimiter) {
        out_offsets[count++] = static_cast<int32_t>(len);
    }
    return count;
}

// 6. ARM64 NEON 64字节位掩码切分 + fast_float::from_chars 原位解析 double
size_t lemire_split_parse_neon_simd(const char* data, size_t len, char delimiter, double* out) {
    const uint8x16_t delim_vec = vdupq_n_u8(static_cast<uint8_t>(delimiter));
    size_t idx = 0;
    size_t token_start = 0;
    size_t count = 0;

    while (idx + 64 <= len) {
        const uint8_t* ptr = reinterpret_cast<const uint8_t*>(data + idx);
        uint8x16_t v0 = vld1q_u8(ptr);
        uint8x16_t v1 = vld1q_u8(ptr + 16);
        uint8x16_t v2 = vld1q_u8(ptr + 32);
        uint8x16_t v3 = vld1q_u8(ptr + 48);

        uint64_t mask = neon_movemask_64bytes(
            vceqq_u8(v0, delim_vec),
            vceqq_u8(v1, delim_vec),
            vceqq_u8(v2, delim_vec),
            vceqq_u8(v3, delim_vec)
        );

        while (mask != 0) {
            unsigned bit = __builtin_ctzll(mask);
            size_t token_end = idx + bit;
            double val = 0.0;
            fast_float::from_chars(data + token_start, data + token_end, val);
            out[count++] = val;
            token_start = token_end + 1;
            mask &= (mask - 1);
        }
        idx += 64;
    }

    // 处理尾部不足 64 字节部分
    while (idx < len) {
        if (data[idx] == delimiter) {
            double val = 0.0;
            fast_float::from_chars(data + token_start, data + idx, val);
            out[count++] = val;
            token_start = idx + 1;
        }
        idx++;
    }
    if (token_start <= len) {
        double val = 0.0;
        fast_float::from_chars(data + token_start, data + len, val);
        out[count++] = val;
    }
    return count;
}

} // extern "C"
