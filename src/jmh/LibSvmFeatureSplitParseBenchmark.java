package jmh;

import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the user's 4096-line feature-pair workload:
 *   Line format: "1:123 2:345 3:789 4:123 5:332 6:123 7:8888 8:223 9:333"
 *   Business logic:
 *     For each of the 4096 lines:
 *       1) String[] tokens = line.split(" ");
 *       2) Loop over tokens, String[] kv = token.split(":");
 *       3) Call Double.parseDouble(...) on the extracted number strings.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Benchmark)
public class LibSvmFeatureSplitParseBenchmark {

    private static final int LINE_COUNT = 4096;
    private String[] lines;

    @Setup(Level.Trial)
    public void setup() {
        lines = new String[LINE_COUNT];
        // Line 0 is the exact user example; subsequent lines follow the exact same 9-pair structure
        lines[0] = "1:123 2:345 3:789 4:123 5:332 6:123 7:8888 8:223 9:333";
        Random rng = new Random(2026);
        int[] baseVals = {123, 345, 789, 123, 332, 123, 8888, 223, 333};
        for (int i = 1; i < LINE_COUNT; i++) {
            StringBuilder sb = new StringBuilder(64);
            for (int k = 0; k < 9; k++) {
                if (k > 0) {
                    sb.append(' ');
                }
                int val = baseVals[k] + rng.nextInt(50);
                sb.append(k + 1).append(':').append(val);
            }
            lines[i] = sb.toString();
        }
    }

    /**
     * Case A: Split 4096 lines by " ", then each token by ":", and parse the value (kv[1]) via Double.parseDouble.
     * Total per operation: 4,096 space-splits + 36,864 colon-splits + 36,864 Double.parseDouble calls.
     */
    @Benchmark
    public double split4096Lines_parseValueOnly() {
        double sum = 0.0;
        String[] inputLines = this.lines;
        for (int i = 0; i < inputLines.length; i++) {
            String[] items = inputLines[i].split(" ");
            for (int j = 0; j < items.length; j++) {
                String[] kv = items[j].split(":");
                sum += Double.parseDouble(kv[1]);
            }
        }
        return sum;
    }

    /**
     * Case B: Split 4096 lines by " ", then each token by ":", and parse BOTH key (kv[0]) and value (kv[1]) via Double.parseDouble.
     * Total per operation: 4,096 space-splits + 36,864 colon-splits + 73,728 Double.parseDouble calls.
     */
    @Benchmark
    public double split4096Lines_parseKeyAndValue() {
        double sum = 0.0;
        String[] inputLines = this.lines;
        for (int i = 0; i < inputLines.length; i++) {
            String[] items = inputLines[i].split(" ");
            for (int j = 0; j < items.length; j++) {
                String[] kv = items[j].split(":");
                double k = Double.parseDouble(kv[0]);
                double v = Double.parseDouble(kv[1]);
                sum += k * v;
            }
        }
        return sum;
    }
}
