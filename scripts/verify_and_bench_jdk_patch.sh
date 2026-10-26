#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p lib build/java.base-classes build/classes build/generated

# 1. Auto-download JMH 1.37 & FastDoubleParser 2.0.1 JARs if missing
MAVEN_BASE="https://repo1.maven.org/maven2"
download_if_missing() {
  local jar_name="$1"
  local maven_path="$2"
  if [[ ! -f "lib/${jar_name}" ]]; then
    echo "==> Downloading ${jar_name} from Maven Central..."
    curl -fsSL "${MAVEN_BASE}/${maven_path}" -o "lib/${jar_name}"
  fi
}

download_if_missing "jmh-core-1.37.jar" "org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar"
download_if_missing "jmh-generator-annprocess-1.37.jar" "org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar"
download_if_missing "jopt-simple-5.0.4.jar" "net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar"
download_if_missing "commons-math3-3.6.1.jar" "org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar"
download_if_missing "fastdoubleparser-2.0.1.jar" "ch/randelshofer/fastdoubleparser/2.0.1/fastdoubleparser-2.0.1.jar"

# 2. Compile patched java.base classes (String.java & FloatingDecimal.java)
echo "==> [1/4] Compiling patched java.base classes (String.java & FloatingDecimal.java)..."
javac --patch-module java.base="$ROOT_DIR/jdk-patch" \
      --add-exports java.base/jdk.internal.math=ALL-UNNAMED \
      --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
      --add-exports java.base/jdk.internal.util=ALL-UNNAMED \
      --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
      -d build/java.base-classes \
      "$ROOT_DIR/jdk-patch/java/lang/String.java" \
      "$ROOT_DIR/jdk-patch/jdk/internal/math/FloatingDecimal.java"

# 3. Compile verification suites and JMH benchmarks
echo "==> [2/4] Compiling AccuracyVerificationSuite, OpenJdkSplitJtregRunner, and JMH benchmarks..."
if [[ ! -f "build/liblemire_native.dylib" && "$(uname -s)" == "Darwin" ]]; then
  ./scripts/build_arm64.sh
fi
javac -proc:full \
      -cp "lib/*:build/classes" \
      -s build/generated \
      -d build/classes \
      src/test/*.java src/bench/*.java src/jmh/*.java

# 4. Run 414,710 Accuracy Checks + 4,177,447 Official OpenJDK jtreg Assertions
echo "==> [3/4] Running 4,592,157 Accuracy & OpenJDK jtreg Assertions against patched java.base..."
java --patch-module java.base=build/java.base-classes -XX:+CompactStrings -cp build/classes test.AccuracyVerificationSuite
java --patch-module java.base=build/java.base-classes -XX:+CompactStrings -cp build/classes test.OpenJdkSplitJtregRunner
java --patch-module java.base=build/java.base-classes -XX:-CompactStrings -cp build/classes test.OpenJdkSplitJtregRunner

# 5. Run JMH Benchmarks (Unpatched vs. Patched java.base)
if [[ "$(uname -s)" == "Darwin" ]]; then
  echo "==> Pre-benchmark CPU Load Check (top):"
  top -l 2 -n 6 -o cpu | tail -n 15
fi

BENCH_REGEX="${1:-jmh.SplitInputShapesBenchmark|jmh.LibSvmFeatureSplitParseBenchmark}"
echo "==> [4/4] Running JMH (${BENCH_REGEX}) — Unpatched JDK vs. Patched JDK..."
echo "--- [A] Unpatched Standard JDK ---"
java --enable-native-access=ALL-UNNAMED \
     -Dlemire.lib="$ROOT_DIR/build/liblemire_native.dylib" \
     -cp "build/classes:lib/*" \
     org.openjdk.jmh.Main "$BENCH_REGEX" -wi 2 -i 4 -r 1s -w 1s -f 1

echo "--- [B] Patched JDK (--patch-module java.base=build/java.base-classes) ---"
java --patch-module java.base=build/java.base-classes \
     --enable-native-access=ALL-UNNAMED \
     -Dlemire.lib="$ROOT_DIR/build/liblemire_native.dylib" \
     -cp "build/classes:lib/*" \
     org.openjdk.jmh.Main "$BENCH_REGEX" -wi 2 -i 4 -r 1s -w 1s -f 1
