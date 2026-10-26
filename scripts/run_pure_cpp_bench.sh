#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
mkdir -p build

echo "=================================================================="
echo " 1. Apple Silicon ARM64 — Pure C++20 Lemire Native Timing"
echo "=================================================================="
CXX="${CXX:-/usr/bin/clang++}"
"$CXX" -O3 -std=c++20 -mcpu=native \
  -I"$ROOT_DIR/native/include" \
  "$ROOT_DIR/native/pure_cpp_bench.cpp" \
  "$ROOT_DIR/native/arm64/lemire_native_arm64.cpp" \
  -o "$ROOT_DIR/build/pure_cpp_bench_arm64"
"$ROOT_DIR/build/pure_cpp_bench_arm64"

echo ""
echo "=================================================================="
echo " 2. Docker + QEMU 10.2.1 RISC-V 64 — Pure C++20 Lemire Native Timing"
echo "=================================================================="
CONTAINER_NAME="lemire-riscv-bench"
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  docker start "${CONTAINER_NAME}" >/dev/null
  docker exec "${CONTAINER_NAME}" bash -c "mkdir -p /work/build /work/native"
  docker cp "${ROOT_DIR}/native/." "${CONTAINER_NAME}:/work/native"
  docker exec "${CONTAINER_NAME}" bash -c "
    set -e
    cd /work
    riscv64-linux-gnu-g++ -O3 -std=c++20 \
      -march=rv64gc_zba_zbb_zbs -mno-strict-align -fno-stack-protector \
      -I/work/native/include \
      /work/native/pure_cpp_bench.cpp \
      /work/native/riscv64/lemire_native_riscv64.cpp \
      -o /work/build/pure_cpp_bench_riscv64
    qemu-riscv64 -cpu max -L /usr/riscv64-linux-gnu /work/build/pure_cpp_bench_riscv64
  "
  docker cp "${CONTAINER_NAME}:/work/build/pure_cpp_bench_riscv64" "${ROOT_DIR}/build/pure_cpp_bench_riscv64"
else
  echo "Container ${CONTAINER_NAME} not found. Run ./scripts/build_and_run_riscv64_docker.sh first."
fi
