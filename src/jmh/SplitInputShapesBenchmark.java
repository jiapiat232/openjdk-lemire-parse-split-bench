package jmh;

import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks String.split across 6 distinct input data shapes to demonstrate
 * how input characteristics (delimiter count, token length, trailing empty tokens)
 * affect performance in Unpatched OpenJDK vs. Patched OpenJDK.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Benchmark)
public class SplitInputShapesBenchmark {

    private String noMatchInput;
    private String singleDelimKvInput;
    private String shortDenseDigitsCsv;
    private String mediumFloatsCsv;
    private String longLogLines;
    private String trailingEmptyDelimsCsv;

    @Setup(Level.Trial)
    public void setup() {
        // 1. No delimiter match (48 bytes)
        noMatchInput = "application.service.endpoint.url.https.port.8443";

        // 2. Single delimiter key=value (53 bytes, 1 delimiter)
        singleDelimKvInput = "application.service.endpoint=https://example.org:8443";

        // 3. Ultra-dense short tokens (256 tokens of 1~2 digits, avgSpan ~ 2.9 bytes)
        StringBuilder sbShort = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            if (i > 0) sbShort.append(',');
            sbShort.append(i % 100);
        }
        shortDenseDigitsCsv = sbShort.toString();

        // 4. Medium CSV tokens (256 floats, avgSpan ~ 16 bytes)
        Random rng = new Random(42);
        StringBuilder sbMed = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            if (i > 0) sbMed.append(',');
            sbMed.append(rng.nextDouble() * 1000.0);
        }
        mediumFloatsCsv = sbMed.toString();

        // 5. Long text/log lines (64 lines separated by '\n', ~96 bytes per line, avgSpan >= 24)
        StringBuilder sbLong = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            if (i > 0) sbLong.append('\n');
            sbLong.append("2026-10-26T23:35:00.123Z INFO  [worker-thread-")
                  .append(100 + i)
                  .append("] com.example.HttpRouter - status=200 latencyMs=12");
        }
        longLogLines = sbLong.toString();

        // 6. Many trailing empty delimiters with limit=0 (3 tokens + 128 trailing commas)
        trailingEmptyDelimsCsv = "header_alpha,header_beta,header_gamma," + ",".repeat(128);
    }

    @Benchmark
    public String[] split_1_no_match() {
        return noMatchInput.split(",");
    }

    @Benchmark
    public String[] split_2_single_delim_kv() {
        return singleDelimKvInput.split("=");
    }

    @Benchmark
    public String[] split_3_short_dense_digits() {
        return shortDenseDigitsCsv.split(",");
    }

    @Benchmark
    public String[] split_4_medium_csv_floats() {
        return mediumFloatsCsv.split(",");
    }

    @Benchmark
    public String[] split_5_long_log_lines() {
        return longLogLines.split("\n");
    }

    @Benchmark
    public String[] split_6_trailing_empty_delims() {
        return trailingEmptyDelimsCsv.split(",");
    }
}
