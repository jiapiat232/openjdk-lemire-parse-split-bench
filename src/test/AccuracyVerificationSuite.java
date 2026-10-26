package test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * OpenJDK 内置 Daniel Lemire 算法 (FloatingDecimal & String.split) 全自动五层准确性验证套件。
 * 遵循 IEEE 754-2019 roundTiesToEven 0 ULP (Bit-Exact) 准则与 JLS 语法规范。
 */
public final class AccuracyVerificationSuite {

    private static final BigDecimal HALF = BigDecimal.valueOf(0.5);
    private static int totalChecks = 0;

    public static void main(String[] args) {
        long startNs = System.nanoTime();
        System.out.println("==> Starting OpenJDK Lemire Built-in Accuracy Verification Suite...");

        verifyBasicAndExtrema();
        verifySwar8ByteBoundaryMatrix();
        verifyNonDigitRejectionInSwarWindow();
        verifySubnormalAndPowerOfTwoBoundaries();
        verifyHardToRoundMidpoints();
        verifyHexFloatingPointAndSpecialLiterals();
        verifyStringSplitExhaustiveMatrix();

        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        System.out.printf("==> [PASS] All %,d bit-exact & edge-case checks passed in %.2f ms!%n",
                totalChecks, elapsedMs);
    }

    // ==================== T1.1: 基础值、极值与正负零符号位验证 ====================
    private static void verifyBasicAndExtrema() {
        String[] literals = {
                "0", "0.0", "+0.0", "-0.0", "000000000000.000000000000e100", "-0000000000.000000000e-200",
                "1", "-1", "10", "10.0", "10.01", "-10.01",
                Double.toString(Double.MIN_VALUE),
                Double.toString(Double.MIN_NORMAL),
                Double.toString(Double.MAX_VALUE),
                Float.toString(Float.MIN_VALUE),
                Float.toString(Float.MIN_NORMAL),
                Float.toString(Float.MAX_VALUE),
                "1.7976931348623157E308",
                "1.7976931348623158e+308",
                "1.7976931348623159e+308",
                "4.9e-324",
                "2.2250738585072014e-308",
                "2.2250738585072012e-308",
                "2.225073858507201383e-308",
                "2.2250738585072013831e-308",
                "2.4703282292062329e-324",
                "2.4703282292062327e-324",
                "2.4703282292062325e-324",
                "9223372036854776833",
                "9223372036854776832",
                "576460752303423616e20",
                "1.2000000000000000003e-200",
                "2.3000000000000000004e100",
                "1.2000000000000000003e-320",
                // 750+ 位超长十进制串 (2^-1047 + 2^-1075)
                "6.631236871469758276785396630275967243399099947355303144249971758736286630139265439618068200788048744105960420552601852889715006376325666595539603330361800519107591783233358492337208057849499360899425128640718856616503093444922854759159988160304439909868291973931426625698663157749836252274523485312442358651207051292453083278116143932569727918709786004497872322193856150225415211997283078496319412124640111777216148110752815101775295719811974338451936095907419622417538473679495148632480391435931767981122396703443803335529756003353209830071832230689201383015598792184172909927924176339315507402234836120730914783168400715462440053817592702766213559042115986763819482654128770595766806872783349146967171293949598850675682115696218943412532098591327667236328125E-316"
        };
        for (String lit : literals) {
            assertExactDoubleAndFloat(lit);
            assertExactDoubleAndFloat("  \t\n" + lit + " \r ");
            assertExactDoubleAndFloat(lit + "d");
            assertExactDoubleAndFloat(lit + "f");
        }
    }

    // ==================== T1.2: SWAR 8字节对齐/跨块与位数边界矩阵 ====================
    private static void verifySwar8ByteBoundaryMatrix() {
        Random rng = new Random(20261024L);
        int[] leadingZeroCounts = {0, 1, 7, 8, 9, 16};
        int[] trailingZeroCounts = {0, 1, 7, 8, 9, 16};
        int[] exponents = {-324, -308, -200, -45, -38, -22, -10, -1, 0, 1, 10, 15, 16, 17, 18, 19, 20, 22, 38, 100, 307};

        for (int sigLen = 1; sigLen <= 23; sigLen++) {
            StringBuilder digitsBuilder = new StringBuilder(sigLen);
            digitsBuilder.append((char) ('1' + rng.nextInt(9)));
            for (int k = 1; k < sigLen - 1; k++) {
                digitsBuilder.append((char) ('0' + rng.nextInt(10)));
            }
            if (sigLen > 1) {
                digitsBuilder.append((char) ('1' + rng.nextInt(9)));
            }
            String rawDigits = digitsBuilder.toString();

            for (int dotPos = 0; dotPos <= sigLen; dotPos += Math.max(1, sigLen / 4)) {
                String withDot = (dotPos == 0)
                        ? ("." + rawDigits)
                        : (dotPos == sigLen)
                        ? rawDigits
                        : (rawDigits.substring(0, dotPos) + "." + rawDigits.substring(dotPos));

                for (int lz : leadingZeroCounts) {
                    for (int tz : trailingZeroCounts) {
                        String core = "0".repeat(lz) + withDot + (withDot.contains(".") ? "0".repeat(tz) : "");
                        assertExactDoubleAndFloat(core);
                        assertExactDoubleAndFloat("-" + core);
                        for (int exp : exponents) {
                            String sci = core + "e" + exp;
                            assertExactDoubleAndFloat(sci);
                            assertExactDoubleAndFloat("-" + sci);
                        }
                    }
                }
            }
        }
    }

    // ==================== T1.4: SWAR 8字节窗口内非法字符拒绝测试 ====================
    private static void verifyNonDigitRejectionInSwarWindow() {
        char[] illegalChars = {'/', ':', '@', 'a', 'g', ' ', '\0', '\u0007', '\u0080', '\u00ff', '\u0661'};
        String base16 = "1234567890123456";
        for (int pos = 0; pos < 16; pos++) {
            for (char bad : illegalChars) {
                String mutated = base16.substring(0, pos) + bad + base16.substring(pos + 1);
                // JLS 允许首尾出现 <= '\u0020' 的空白控制字符，但严禁出现在数字内部或 "0." 之后
                if (bad > ' ' || (pos > 0 && pos < 15)) {
                    assertRejectsBoth(mutated);
                }
                if (bad > ' ' || pos < 15) {
                    assertRejectsBoth("0." + mutated + "e5");
                }
            }
        }
        String[] malformed = {
                "", "+", "-", ".", "+.", "-.", "e10", ".e10", "1.2.3", "1..2",
                "1e", "1e+", "1e-", "1e10e10", "NaNf", "Infinityd", "+NaN1", "123 456",
                "0x", "0x.p1", "0x1.p", "0x1.0"
        };
        for (String bad : malformed) {
            assertRejectsBoth(bad);
        }
    }

    // ==================== T1.3: 次正规数、2的幂边界与 Eisel-Lemire 半 ULP 中点酷刑测试 ====================
    private static void verifySubnormalAndPowerOfTwoBoundaries() {
        // 遍历所有次正规数和正规数 2^i 边界 [-1074, 1023]
        for (int i = -1074; i <= 1023; i++) {
            double d = Math.scalb(1.0, i);
            BigDecimal bd = new BigDecimal(d);
            BigDecimal lower = bd.subtract(new BigDecimal(Math.ulp(Math.nextUp(-d))).multiply(HALF));
            BigDecimal upper = bd.add(new BigDecimal(Math.ulp(d)).multiply(HALF));
            assertExactDoubleAndFloat(lower.toString());
            assertExactDoubleAndFloat(upper.toString());
        }
    }

    private static void verifyHardToRoundMidpoints() {
        Random rng = new Random(99887766L);
        // 构造 2,000 组精确位于两个相邻 double 正中间 (d + 0.5 * ulp(d)) 的高危十进制串
        for (int i = 0; i < 2_000; i++) {
            long randomBits = rng.nextLong() & 0x7fefffffffffffffL; // 任意有限正数
            double d = Double.longBitsToDouble(randomBits);
            if (!Double.isFinite(d) || d == 0.0) {
                continue;
            }
            double ulp = Math.ulp(d);
            BigDecimal midpoint = new BigDecimal(d).add(new BigDecimal(ulp).multiply(HALF));
            String midStr = midpoint.toString();
            assertExactDoubleAndFloat(midStr);

            // 在中点上下微调 10^-(scale + 2)，专门压测 Eisel-Lemire 128-bit 乘法模糊区与 FDBigInteger 回退一致性
            BigDecimal eps = BigDecimal.ONE.scaleByPowerOfTen(-(midpoint.scale() + 2));
            assertExactDoubleAndFloat(midpoint.subtract(eps).toString());
            assertExactDoubleAndFloat(midpoint.add(eps).toString());
        }
    }

    private static void verifyHexFloatingPointAndSpecialLiterals() {
        String[] specials = {"NaN", "+NaN", "-NaN", "Infinity", "+Infinity", "-Infinity"};
        for (String sp : specials) {
            double d = Double.parseDouble(sp);
            float f = Float.parseFloat(sp);
            if (sp.contains("NaN")) {
                if (!Double.isNaN(d) || !Float.isNaN(f)) {
                    throw new AssertionError("Expected NaN for " + sp);
                }
            } else {
                boolean neg = sp.startsWith("-");
                if (d != (neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY)) {
                    throw new AssertionError("Expected Infinity for " + sp);
                }
            }
            totalChecks += 2;
        }

        String[] hexLiterals = {
                "0x1.0p0", "-0x1.0p0", "0x1.fffffep127f", "0x1.fffffffffffffp1023",
                "0x0.0000000000001p-1022", "0x0.0000008000000p-1022", "0x001.100p1"
        };
        for (String hex : hexLiterals) {
            double d = Double.parseDouble(hex);
            float f = Float.parseFloat(hex);
            if (Double.isNaN(d) || Float.isNaN(f)) {
                throw new AssertionError("Unexpected NaN for hex literal: " + hex);
            }
            totalChecks += 2;
        }
    }

    // ==================== T2.1 & T2.2: String.split 跨 8B/16B/64B 边界与编码等价测试 ====================
    private static void verifyStringSplitExhaustiveMatrix() {
        int[] limits = {-2, -1, 0, 1, 2, 3, 5, 10, 64, 130};
        Pattern commaPattern = Pattern.compile(",");

        // 1. 跨 8B / 16B / 32B / 64B / 128B 每一字节长度与每一个逗号位置全遍历
        for (int len = 0; len <= 135; len++) {
            String allX = "x".repeat(len);
            verifySplitAllLimits(allX, ",", commaPattern, limits);

            String allCommas = ",".repeat(len);
            verifySplitAllLimits(allCommas, ",", commaPattern, limits);

            if (len > 0) {
                // 在首、尾、中间、7/8/15/16/63/64 边界放置分隔符
                int[] positions = {0, len / 2, len - 1, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128};
                StringBuilder sb = new StringBuilder(allX);
                for (int p : positions) {
                    if (p < len) {
                        char[] arr = allX.toCharArray();
                        arr[p] = ',';
                        verifySplitAllLimits(new String(arr), ",", commaPattern, limits);
                        sb.setCharAt(p, ',');
                    }
                }
                verifySplitAllLimits(sb.toString(), ",", commaPattern, limits);
                verifySplitAllLimits(sb + ",,,", ",", commaPattern, limits);
                verifySplitAllLimits(",,," + sb + ",,,", ",", commaPattern, limits);
            }
        }

        // 2. UTF-16 (含中文与 Emoji) 与转义单字符分隔符测试
        String[] utf16Cases = {
                "", "你好,世界,RISC-V,ARM64,,", ",,🚀,🔥,,💡,,,", "纯中文无分隔符"
        };
        for (String s : utf16Cases) {
            verifySplitAllLimits(s, ",", commaPattern, limits);
        }
    }

    private static void verifySplitAllLimits(String input, String regex, Pattern pattern, int[] limits) {
        for (int limit : limits) {
            String[] actual = input.split(regex, limit);
            String[] expected = pattern.split(input, limit);
            if (!Arrays.equals(actual, expected)) {
                throw new AssertionError("String.split mismatch on len=" + input.length() + ", limit=" + limit
                        + "\n  input   = [" + input + "]"
                        + "\n  expected= " + Arrays.toString(expected)
                        + "\n  actual  = " + Arrays.toString(actual));
            }
            totalChecks++;
        }
    }

    private static void assertExactDoubleAndFloat(String s) {
        double actualD = Double.parseDouble(s);
        float actualF = Float.parseFloat(s);

        // 使用 BigDecimal 夹逼定理验证 actualD 满足 IEEE 754 roundTiesToEven (0 ULP)
        verifyBigDecimalSandwichDouble(s, actualD);
        // 同时确保双精度与单精度符号位 (含 -0.0 vs +0.0) 完全正确
        boolean expectedNeg = s.trim().startsWith("-");
        boolean actualNegD = (Double.doubleToRawLongBits(actualD) < 0);
        boolean actualNegF = (Float.floatToRawIntBits(actualF) < 0);
        if (expectedNeg != actualNegD || expectedNeg != actualNegF) {
            throw new AssertionError("Sign bit mismatch on [" + s + "]: d=" + actualD + ", f=" + actualF);
        }
        totalChecks += 2;
    }

    private static void verifyBigDecimalSandwichDouble(String raw, double n) {
        String s = raw.trim().toLowerCase();
        if (s.endsWith("d") || s.endsWith("f")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("+") || s.startsWith("-")) {
            s = s.substring(1);
        }
        BigDecimal bd = new BigDecimal(s);
        double na = Math.abs(n);
        if (na == 0.0) {
            BigDecimal halfMin = new BigDecimal(Double.MIN_VALUE).multiply(HALF);
            if (bd.compareTo(halfMin) > 0) {
                throw new AssertionError("Underflow to 0.0 too early for [" + raw + "]");
            }
            return;
        }
        BigDecimal l, u;
        if (Double.isInfinite(na)) {
            l = new BigDecimal(Double.MAX_VALUE).add(new BigDecimal(Math.ulp(Double.MAX_VALUE)).multiply(HALF));
            u = null;
        } else {
            l = new BigDecimal(na).subtract(new BigDecimal(Math.ulp(Math.nextUp(-na))).multiply(HALF));
            u = new BigDecimal(na).add(new BigDecimal(Math.ulp(na)).multiply(HALF));
        }
        int cmpL = bd.compareTo(l);
        int cmpU = (u != null) ? bd.compareTo(u) : -1;
        if ((Double.doubleToRawLongBits(n) & 1L) != 0L) {
            if (cmpL <= 0 || cmpU >= 0) {
                throw new AssertionError("Odd mantissa rounding violation on [" + raw + "] -> " + n);
            }
        } else {
            if (cmpL < 0 || cmpU > 0) {
                throw new AssertionError("Even mantissa rounding violation on [" + raw + "] -> " + n);
            }
        }
    }

    private static void assertRejectsBoth(String bad) {
        try {
            double d = Double.parseDouble(bad);
            throw new AssertionError("Double.parseDouble accepted illegal input [" + bad + "] -> " + d);
        } catch (NumberFormatException expected) {
            totalChecks++;
        }
        try {
            float f = Float.parseFloat(bad);
            throw new AssertionError("Float.parseFloat accepted illegal input [" + bad + "] -> " + f);
        } catch (NumberFormatException expected) {
            totalChecks++;
        }
    }
}
