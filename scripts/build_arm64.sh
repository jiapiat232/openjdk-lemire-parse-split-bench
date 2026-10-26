#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p build/classes

CXX="${CXX:-/usr/bin/clang++}"
echo "==> [1/2] Compiling Apple Silicon (ARM64 NEON) Native Library: build/liblemire_native.dylib"
"$CXX" -O3 -std=c++20 -mcpu=native -shared -fPIC \
  -I"$ROOT_DIR/native/include" \
  "$ROOT_DIR/native/arm64/lemire_native_arm64.cpp" \
  -o "$ROOT_DIR/build/liblemire_native.dylib"

echo "==> [2/2] Compiling JMH Benchmark (Java + JEP 454 FFM): build/classes"
javac -proc:full \
  -cp "lib/*" \
  -d build/classes \
  src/bench/LemireFfmBenchmark.java

echo "==> Build completed successfully."
