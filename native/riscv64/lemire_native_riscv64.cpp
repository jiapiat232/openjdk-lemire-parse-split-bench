/**
 * ============================================================================
 * Daniel Lemire 算法 RISC-V 64 (RV64GCV + Zba/Zbb/Zbs) 移植与适配实现
 * ============================================================================
 *
 * 【RISC-V 64 架构适配说明 (RISC-V Porting & Optimization Notes)】
 *
 * 1. fast_float 在 RISC-V 64 下的两处关键硬件适配：
 *    - (a) 128-bit 乘法 (`value128 full_multiplication`):
 *          `fast_float.h` 检测到 64 位环境的 `__SIZEOF_INT128__` 宏，使用 `__uint128_t`
 *          直接映射为 RISC-V 硬件指令对 `mul` (低 64 位) + `mulhu` (无符号高 64 位)。
 *    - (b) SWAR 8位数字块加载 (`read8_to_u64` 中的 `std::memcpy(&val, chars, 8)`) 对齐陷阱修复：
 *          若直接全局开启 `-march=rv64gcv` 且不加 `-mno-strict-align`，GCC 15 会因 RISC-V
 *          默认 Strict Alignment 策略，将 8 字节非对齐 `memcpy` 错误向量化为 4 条指令：
 *          `vsetivli zero,8,e8,m1` -> `vle8.v v1,(a0)` -> `vse8.v v1,stack` -> `ld a5,stack`，
 *          导致每次解析多出栈内存写读转发延迟（实测慢 5.76 倍）。
 *          **修复方案**：编译时指定 `-march=rv64gc_zba_zbb_zbs -mno-strict-align`，使 `fast_float`
 *          保持纯标量单条 `ld` 非对齐加载，仅在需要向量化的切分函数上使用
 *          `__attribute__((target("arch=rv64gcv_zbb")))` 局部开启 RVV 1.0。
 *
 * 2. String.split 的 RISC-V Vector 1.0 (RVV) + Zbb 位掩码适配：
 *    - 对照 `../../upstream/simdjson/include/simdjson/rvv-vls/simd.h` (第 320-358 行)：
 *      * 使用 `__riscv_vsetvl_e8m4(64)` + `__riscv_vle8_v_u8m4` 一次加载 64 字节；
 *      * 使用 `__riscv_vmseq_vx_u8m4_b2` 并行比较 64 字节与分隔符，硬件直接产出 64-bit 掩码寄存器 `vbool2_t`
 *        （无需像 ARM64 NEON 那样执行 8 条 `vandq_u8` + `vpaddq_u8` 归约压缩）；
 *      * 使用 `__riscv_vreinterpret_v_b2_u64m1` + `__riscv_vmv_x_s_u64m1_u64` (`vmv.x.s`)
 *        单指令将掩码寄存器直接移入通用标量寄存器 `uint64_t mask`；
 *      * 使用 RISC-V `Zbb` 扩展的硬件 `ctz` 指令（`__builtin_ctzll(mask)`）与 `mask &= (mask - 1)`
 *        无分支提取每个分隔符偏移量。
 * ============================================================================
 */

#include "../include/fast_float.h"
#include <cstddef>
#include <cstdint>
#include <limits>
#include <ranges>
#include <string_view>

extern "C" {

// 1. RISC-V RV64GC_Zba_Zbb_Zbs (mul + mulhu + 单条非对齐 ld) 调用 fast_float::from_chars 解析 double
double lemire_parse_double(const char* str, size_t len) {
    double result = std::numeric_limits<double>::quiet_NaN();
    fast_float::from_chars(str, str + len, result);
    return result;
}

// 2. RISC-V RV64GC_Zba_Zbb_Zbs 调用 fast_float::from_chars 解析 float
float lemire_parse_float(const char* str, size_t len) {
    float result = std::numeric_limits<float>::quiet_NaN();
    fast_float::from_chars(str, str + len, result);
    return result;
}

// 3. Lemire C++20 std::views::split + std::string_view + fast_float::from_chars
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

// 4. RISC-V Vector 1.0 (RVV) 64字节并行比较转 64-bit 位掩码 (uint64_t)
// 采用局部 `.option arch, +v` 内联汇编：
// - 既让 64 字节分隔符扫描直接内联为 `vle8.v` + `vmseq.vx` + `vmv.x.s` 硬件向量指令，
// - 又避免 GCC 在整个函数作用域开启自动向量化从而污染 `fast_float::from_chars` 的标量流水线。
static inline uint64_t rvv_movemask_64bytes(const uint8_t* ptr, uint8_t delimiter) {
    uint64_t mask;
    asm volatile(
        ".option push\n\t"
        ".option arch, +v\n\t"
        "li t0, 64\n\t"
        "vsetvli zero, t0, e8, m4, ta, ma\n\t"
        "vle8.v v4, (%1)\n\t"
        "vmseq.vx v0, v4, %2\n\t"
        "vsetivli zero, 1, e64, m1, ta, ma\n\t"
        "vmv.x.s %0, v0\n\t"
        ".option pop\n\t"
        : "=r"(mask)
        : "r"(ptr), "r"(delimiter)
        : "t0", "memory"
    );
    return mask;
}

// 5. 纯字符串切分：使用 RISC-V RVV 1.0 + Zbb (ctz) 提取所有分隔符偏移量
// (函数符号名保持 lemire_split_offsets_neon 以便与 Java 侧 FFM SymbolLookup 无缝复用)
size_t lemire_split_offsets_neon(const char* data, size_t len, char delimiter, int32_t* out_offsets) {
    const uint8_t delim_u8 = static_cast<uint8_t>(delimiter);
    size_t idx = 0;
    size_t count = 0;

    while (idx + 64 <= len) {
        const uint8_t* ptr = reinterpret_cast<const uint8_t*>(data + idx);
        uint64_t mask = rvv_movemask_64bytes(ptr, delim_u8);

        while (mask != 0) {
            // 在 -march=rv64gc_zba_zbb_zbs 下编译为单条 RISC-V 硬件指令: ctz
            unsigned bit = __builtin_ctzll(mask);
            out_offsets[count++] = static_cast<int32_t>(idx + bit);
            mask &= (mask - 1);
        }
        idx += 64;
    }

    while (idx < len) {
        if (data[idx] == delimiter) {
            out_offsets[count++] = static_cast<int32_t>(idx);
        }
        idx++;
    }
    out_offsets[count++] = static_cast<int32_t>(len);
    return count;
}

// 5b. 纯字符串切分：使用 Lemire C++20 std::views::split 提取所有分隔符偏移量
size_t lemire_split_offsets_cxx20_views(const char* data, size_t len, char delimiter, int32_t* out_offsets) {
    std::string_view sv(data, len);
    size_t count = 0;
    for (auto&& part : sv | std::views::split(delimiter)) {
        const char* last = &*part.begin() + std::ranges::distance(part);
        out_offsets[count++] = static_cast<int32_t>(last - data);
    }
    if (len == 0 || data[len - 1] == delimiter) {
        out_offsets[count++] = static_cast<int32_t>(len);
    }
    return count;
}

// 6. RISC-V RVV 1.0 64字节位掩码切分 + fast_float::from_chars 原位解析 double
size_t lemire_split_parse_neon_simd(const char* data, size_t len, char delimiter, double* out) {
    const uint8_t delim_u8 = static_cast<uint8_t>(delimiter);
    size_t idx = 0;
    size_t token_start = 0;
    size_t count = 0;

    while (idx + 64 <= len) {
        const uint8_t* ptr = reinterpret_cast<const uint8_t*>(data + idx);
        uint64_t mask = rvv_movemask_64bytes(ptr, delim_u8);

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
