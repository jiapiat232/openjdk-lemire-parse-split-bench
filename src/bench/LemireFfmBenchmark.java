package bench;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import ch.randelshofer.fastdoubleparser.JavaFloatParser;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * OpenJDK 标准实现 vs. Daniel Lemire 算法 (纯 Java FastDoubleParser & JEP 454 FFM Native)
 * 支持在 macOS Apple Silicon (arm64) 与 Linux RISC-V 64 (riscv64) 下运行。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
        "--enable-native-access=ALL-UNNAMED"
})
public class LemireFfmBenchmark {

    // ==================== JEP 454 Foreign Function & Memory API 绑定 ====================
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB_LOOKUP;

    // 1. 普通 Downcall Handle（需传入 Off-Heap MemorySegment，包含标准 Java->Native 线程状态切换）
    private static final MethodHandle MH_PARSE_DOUBLE_NORMAL;
    // 2. JEP 454 Critical Downcall Handle：
    //    - Linker.Option.critical(false): 消除 JVM 线程状态转换开销（适用于极短小的纯 CPU 计算函数）
    //    - Linker.Option.critical(true):  不仅消除状态转换开销，还允许零拷贝直接传递 Java 堆数组 MemorySegment.ofArray(byte[] / double[])
    private static final MethodHandle MH_PARSE_DOUBLE_CRITICAL_OFFHEAP;
    private static final MethodHandle MH_PARSE_DOUBLE_CRITICAL_HEAP;
    private static final MethodHandle MH_PARSE_FLOAT_CRITICAL_HEAP;
    private static final MethodHandle MH_SPLIT_OFFSETS_NEON_CRITICAL;
    private static final MethodHandle MH_SPLIT_OFFSETS_CXX20_VIEWS_CRITICAL;
    private static final MethodHandle MH_SPLIT_PARSE_CXX20_VIEWS_CRITICAL;
    private static final MethodHandle MH_SPLIT_PARSE_NEON_SIMD_CRITICAL;

    static {
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        String defaultLibName = isMac ? "liblemire_native.dylib" : "liblemire_native_riscv64.so";
        String customProp = System.getProperty("lemire.lib");
        Path dylibPath;
        if (customProp != null && !customProp.isBlank()) {
            dylibPath = Path.of(customProp).toAbsolutePath();
        } else {
            Path inBuild = Path.of(System.getProperty("user.dir"), "build", defaultLibName).toAbsolutePath();
            Path inRoot = Path.of(System.getProperty("user.dir"), defaultLibName).toAbsolutePath();
            dylibPath = Files.exists(inBuild) ? inBuild : inRoot;
        }
        LIB_LOOKUP = SymbolLookup.libraryLookup(dylibPath, Arena.global());

        // double lemire_parse_double(const char* str, size_t len)
        FunctionDescriptor parseDoubleDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG
        );
        MH_PARSE_DOUBLE_NORMAL = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_parse_double"),
                parseDoubleDesc
        );
        MH_PARSE_DOUBLE_CRITICAL_OFFHEAP = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_parse_double"),
                parseDoubleDesc,
                Linker.Option.critical(false)
        );
        MH_PARSE_DOUBLE_CRITICAL_HEAP = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_parse_double"),
                parseDoubleDesc,
                Linker.Option.critical(true)
        );

        // float lemire_parse_float(const char* str, size_t len)
        FunctionDescriptor parseFloatDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_FLOAT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG
        );
        MH_PARSE_FLOAT_CRITICAL_HEAP = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_parse_float"),
                parseFloatDesc,
                Linker.Option.critical(true)
        );

        // size_t lemire_split_offsets_neon(const char* data, size_t len, char delimiter, int32_t* out_offsets)
        FunctionDescriptor splitOffsetsDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_BYTE,
                ValueLayout.ADDRESS
        );
        MH_SPLIT_OFFSETS_NEON_CRITICAL = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_split_offsets_neon"),
                splitOffsetsDesc,
                Linker.Option.critical(true)
        );
        MH_SPLIT_OFFSETS_CXX20_VIEWS_CRITICAL = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_split_offsets_cxx20_views"),
                splitOffsetsDesc,
                Linker.Option.critical(true)
        );

        // size_t lemire_split_parse_*(const char* data, size_t len, char delimiter, double* out)
        FunctionDescriptor splitParseDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_BYTE,
                ValueLayout.ADDRESS
        );
        MH_SPLIT_PARSE_CXX20_VIEWS_CRITICAL = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_split_parse_cxx20_views"),
                splitParseDesc,
                Linker.Option.critical(true)
        );
        MH_SPLIT_PARSE_NEON_SIMD_CRITICAL = LINKER.downcallHandle(
                LIB_LOOKUP.findOrThrow("lemire_split_parse_neon_simd"),
                splitParseDesc,
                Linker.Option.critical(true)
        );
    }

    // ==================== 测试数据集 ====================
    private static final int NUM_COUNT = 256;
    private String[] numberStrings;
    private byte[][] numberUtf8Bytes;
    private MemorySegment[] offHeapNumberSegments;
    private long[] numberLengths;

    // 拼接成的一整行逗号分隔浮点数字符串（包含 256 个浮点数，约 4.4 KB）
    private String csvLine;
    private byte[] csvLineUtf8;
    private MemorySegment csvLineHeapSegment;
    private int[] outOffsetsBuffer;
    private MemorySegment outOffsetsHeapSegment;
    private double[] outDoublesBuffer;
    private MemorySegment outDoublesHeapSegment;

    private Arena arena;

    @Setup(Level.Trial)
    public void setup() throws Throwable {
        arena = Arena.ofConfined();
        Random rng = new Random(42L);
        numberStrings = new String[NUM_COUNT];
        numberUtf8Bytes = new byte[NUM_COUNT][];
        offHeapNumberSegments = new MemorySegment[NUM_COUNT];
        numberLengths = new long[NUM_COUNT];

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NUM_COUNT; i++) {
            double raw = (rng.nextDouble() - 0.5) * Math.pow(10, rng.nextInt(-150, 150));
            String s = Double.toString(raw);
            numberStrings[i] = s;
            byte[] utf8 = s.getBytes(StandardCharsets.US_ASCII);
            numberUtf8Bytes[i] = utf8;
            numberLengths[i] = utf8.length;

            MemorySegment seg = arena.allocate(utf8.length);
            MemorySegment.copy(utf8, 0, seg, ValueLayout.JAVA_BYTE, 0, utf8.length);
            offHeapNumberSegments[i] = seg;

            if (i > 0) {
                sb.append(',');
            }
            sb.append(s);
        }

        csvLine = sb.toString();
        csvLineUtf8 = csvLine.getBytes(StandardCharsets.US_ASCII);
        csvLineHeapSegment = MemorySegment.ofArray(csvLineUtf8);

        outOffsetsBuffer = new int[NUM_COUNT + 16];
        outOffsetsHeapSegment = MemorySegment.ofArray(outOffsetsBuffer);

        outDoublesBuffer = new double[NUM_COUNT + 16];
        outDoublesHeapSegment = MemorySegment.ofArray(outDoublesBuffer);

        // 启动时自动校验所有实现的计算结果是否 100% 精确一致（IEEE 754 bit-exact）
        for (int i = 0; i < NUM_COUNT; i++) {
            double expected = Double.parseDouble(numberStrings[i]);
            double actualFfm = (double) MH_PARSE_DOUBLE_CRITICAL_HEAP.invokeExact(
                    MemorySegment.ofArray(numberUtf8Bytes[i]),
                    numberLengths[i]
            );
            if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actualFfm)) {
                throw new AssertionError("Double mismatch at " + i + ": expected=" + expected + ", actual=" + actualFfm);
            }
        }
        long countNeon = (long) MH_SPLIT_PARSE_NEON_SIMD_CRITICAL.invokeExact(
                csvLineHeapSegment, (long) csvLineUtf8.length, (byte) ',', outDoublesHeapSegment
        );
        if (countNeon != NUM_COUNT) {
            throw new AssertionError("Split count mismatch: " + countNeon + " != " + NUM_COUNT);
        }
        for (int i = 0; i < NUM_COUNT; i++) {
            double expected = Double.parseDouble(numberStrings[i]);
            if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(outDoublesBuffer[i])) {
                throw new AssertionError("SIMD split+parse mismatch at " + i);
            }
        }

        // 校验 String.split 的所有边界语义（包含空串、连续分隔符、末尾空串裁剪 limit=0 vs limit=-1、64字节跨块边界）
        String[] edgeCases = {
                "",
                ",",
                ",,,,",
                ",,,a,,b,,,",
                "a,b,c,",
                ",a,b,c",
                "no_delimiter_here",
                "x".repeat(63) + "," + "y".repeat(64) + "," + "z".repeat(65) + ",,,",
                csvLine
        };
        for (String tc : edgeCases) {
            for (int limit : new int[]{0, -1, 3}) {
                String[] expected = tc.split(",", limit);
                byte[] bytes = tc.getBytes(StandardCharsets.US_ASCII);
                String[] actual = splitWithFfmNeonExactJdkSemantics(
                        tc, MemorySegment.ofArray(bytes), bytes.length, (byte) ',', limit,
                        outOffsetsBuffer, outOffsetsHeapSegment
                );
                if (!java.util.Arrays.equals(expected, actual)) {
                    throw new AssertionError("String.split mismatch on [" + tc + "] limit=" + limit
                            + ": expected=" + java.util.Arrays.toString(expected)
                            + ", actual=" + java.util.Arrays.toString(actual));
                }
            }
        }
    }

    /**
     * 使用 JEP 454 + Lemire SIMD (ARM64 NEON / RISC-V RVV 1.0) 一次性扫描出所有分隔符偏移量，
     * 并严格按照 JDK String.split(char, int) 的语义（含 limit=0 丢弃末尾空串、无匹配返回 new String[]{s}）
     * 直接分配精确大小的 String[] 数组（省去 JDK 内部 ArrayList 扩容与 toArray 二次拷贝）。
     */
    private static String[] splitWithFfmNeonExactJdkSemantics(
            String s, MemorySegment utf8Seg, int len, byte delimiter, int limit,
            int[] offsetsBuf, MemorySegment offsetsSeg) throws Throwable {
        int totalParts = (int) (long) MH_SPLIT_OFFSETS_NEON_CRITICAL.invokeExact(
                utf8Seg, (long) len, delimiter, offsetsSeg
        );
        if (totalParts == 1) {
            return new String[]{s};
        }
        int resultSize = totalParts;
        if (limit > 0 && resultSize > limit) {
            resultSize = limit;
        } else if (limit == 0) {
            while (resultSize > 0) {
                int start = (resultSize == 1) ? 0 : (offsetsBuf[resultSize - 2] + 1);
                int end = offsetsBuf[resultSize - 1];
                if (start == end) {
                    resultSize--;
                } else {
                    break;
                }
            }
        }
        String[] result = new String[resultSize];
        int start = 0;
        for (int i = 0; i < resultSize; i++) {
            int end = (limit > 0 && i == resultSize - 1) ? len : offsetsBuf[i];
            result[i] = s.substring(start, end);
            start = end + 1;
        }
        return result;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== 基准测试组 1：解析 256 个独立浮点数 (parseDouble / parseFloat) ====================

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public double parseDouble_1_JdkStandard() {
        double sum = 0.0;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += Double.parseDouble(numberStrings[i]);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public double parseDouble_2_FastDoubleParser_PureJava() {
        double sum = 0.0;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += JavaDoubleParser.parseDouble(numberUtf8Bytes[i]);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public double parseDouble_3_FfmLemireFastFloat_NormalOffHeap() throws Throwable {
        double sum = 0.0;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += (double) MH_PARSE_DOUBLE_NORMAL.invokeExact(
                    offHeapNumberSegments[i],
                    numberLengths[i]
            );
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public double parseDouble_4_FfmLemireFastFloat_CriticalOffHeap() throws Throwable {
        double sum = 0.0;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += (double) MH_PARSE_DOUBLE_CRITICAL_OFFHEAP.invokeExact(
                    offHeapNumberSegments[i],
                    numberLengths[i]
            );
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public double parseDouble_5_FfmLemireFastFloat_CriticalHeapZeroCopy() throws Throwable {
        double sum = 0.0;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += (double) MH_PARSE_DOUBLE_CRITICAL_HEAP.invokeExact(
                    MemorySegment.ofArray(numberUtf8Bytes[i]),
                    numberLengths[i]
            );
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public float parseFloat_1_JdkStandard() {
        float sum = 0.0f;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += Float.parseFloat(numberStrings[i]);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public float parseFloat_2_FastFloatParser_PureJava() {
        float sum = 0.0f;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += JavaFloatParser.parseFloat(numberUtf8Bytes[i]);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_COUNT)
    public float parseFloat_3_FfmLemireFastFloat_CriticalHeapZeroCopy() throws Throwable {
        float sum = 0.0f;
        for (int i = 0; i < NUM_COUNT; i++) {
            sum += (float) MH_PARSE_FLOAT_CRITICAL_HEAP.invokeExact(
                    MemorySegment.ofArray(numberUtf8Bytes[i]),
                    numberLengths[i]
            );
        }
        return sum;
    }

    // ==================== 基准测试组 2：纯字符串切分 (String.split vs C++20 views::split vs SIMD Bitmask) ====================

    @Benchmark
    public String[] splitOnly_1_JdkStringSplit() {
        return csvLine.split(",");
    }

    @Benchmark
    public String[] splitOnly_2_FfmNeonSimd_ReturnExactJavaStringArray() throws Throwable {
        return splitWithFfmNeonExactJdkSemantics(
                csvLine, csvLineHeapSegment, csvLineUtf8.length, (byte) ',', 0,
                outOffsetsBuffer, outOffsetsHeapSegment
        );
    }

    @Benchmark
    public long splitOnly_3_FfmLemireCpp20ViewsSplit_ZeroCopyOffsets(Blackhole bh) throws Throwable {
        long count = (long) MH_SPLIT_OFFSETS_CXX20_VIEWS_CRITICAL.invokeExact(
                csvLineHeapSegment,
                (long) csvLineUtf8.length,
                (byte) ',',
                outOffsetsHeapSegment
        );
        bh.consume(outOffsetsBuffer);
        return count;
    }

    @Benchmark
    public long splitOnly_4_FfmLemireNeonSimd_ZeroCopyOffsets(Blackhole bh) throws Throwable {
        long count = (long) MH_SPLIT_OFFSETS_NEON_CRITICAL.invokeExact(
                csvLineHeapSegment,
                (long) csvLineUtf8.length,
                (byte) ',',
                outOffsetsHeapSegment
        );
        bh.consume(outOffsetsBuffer);
        return count;
    }

    // ==================== 基准测试组 3：切分 + 解析 256 个浮点数整行流水线 ====================

    @Benchmark
    public double splitAndParse_1_JdkSplitAndParseDouble(Blackhole bh) {
        String[] parts = csvLine.split(",");
        double sum = 0.0;
        for (int i = 0; i < parts.length; i++) {
            double v = Double.parseDouble(parts[i]);
            outDoublesBuffer[i] = v;
            sum += v;
        }
        bh.consume(outDoublesBuffer);
        return sum;
    }

    @Benchmark
    public double splitAndParse_2_PureJava_IndexOfAndFastDoubleParser(Blackhole bh) {
        int start = 0;
        int idx = 0;
        double sum = 0.0;
        int len = csvLine.length();
        while (start <= len) {
            int comma = csvLine.indexOf(',', start);
            int end = (comma == -1) ? len : comma;
            double v = JavaDoubleParser.parseDouble(csvLine, start, end - start);
            outDoublesBuffer[idx++] = v;
            sum += v;
            if (comma == -1) {
                break;
            }
            start = comma + 1;
        }
        bh.consume(outDoublesBuffer);
        return sum;
    }

    @Benchmark
    public double splitAndParse_3_FfmLemireCpp20ViewsSplitAndFastFloat(Blackhole bh) throws Throwable {
        long count = (long) MH_SPLIT_PARSE_CXX20_VIEWS_CRITICAL.invokeExact(
                csvLineHeapSegment,
                (long) csvLineUtf8.length,
                (byte) ',',
                outDoublesHeapSegment
        );
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += outDoublesBuffer[i];
        }
        bh.consume(outDoublesBuffer);
        return sum;
    }

    @Benchmark
    public double splitAndParse_4_FfmLemireNeonSimdSplitAndFastFloat(Blackhole bh) throws Throwable {
        long count = (long) MH_SPLIT_PARSE_NEON_SIMD_CRITICAL.invokeExact(
                csvLineHeapSegment,
                (long) csvLineUtf8.length,
                (byte) ',',
                outDoublesHeapSegment
        );
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += outDoublesBuffer[i];
        }
        bh.consume(outDoublesBuffer);
        return sum;
    }
}
