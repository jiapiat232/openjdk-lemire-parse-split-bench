#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f "build/liblemire_native.dylib" || ! -f "build/classes/bench/LemireFfmBenchmark.class" ]]; then
  ./scripts/build_arm64.sh
fi

echo "==> Pre-benchmark CPU Load Check (top):"
top -l 2 -n 6 -o cpu | tail -n 16

echo "==> Running JMH Benchmark on macOS Apple Silicon (arm64)..."
java --enable-native-access=ALL-UNNAMED \
  -Dlemire.lib="$ROOT_DIR/build/liblemire_native.dylib" \
  -cp "build/classes:lib/*" \
  org.openjdk.jmh.Main \
  "${@:-bench.LemireFfmBenchmark}"
