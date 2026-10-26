#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# 确保本地已生成 JMH 字节码 classes
if [[ ! -f "build/classes/bench/LemireFfmBenchmark.class" ]]; then
  ./scripts/build_arm64.sh
fi

CONTAINER_NAME="lemire-riscv-bench"

# 若容器存在但未挂载当前工程目录，或容器不存在，则自动创建/启动并安装 QEMU 10.2.1 + GCC 15 + RISC-V OpenJDK 26
if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "==> Creating Ubuntu 26.04 LTS container (${CONTAINER_NAME})..."
  docker run -d --name "${CONTAINER_NAME}" -v "${ROOT_DIR}:/workspace" ubuntu:latest sleep infinity
fi

docker start "${CONTAINER_NAME}" >/dev/null

# 将当前工程的核心编译目录同步复制到容器内 /work
docker exec "${CONTAINER_NAME}" bash -c "mkdir -p /work/build /work/native /work/lib"
docker cp "${ROOT_DIR}/native/." "${CONTAINER_NAME}:/work/native"
docker cp "${ROOT_DIR}/lib/." "${CONTAINER_NAME}:/work/lib"
docker cp "${ROOT_DIR}/build/classes" "${CONTAINER_NAME}:/work/build/"

echo "==> [1/2] Cross-compiling RISC-V 64 (RV64GC_Zba_Zbb_Zbs + RVV 1.0) Native Library inside Docker..."
docker exec "${CONTAINER_NAME}" bash -c "
  set -e
  cd /work
  riscv64-linux-gnu-g++ -O3 -std=c++20 \
    -march=rv64gc_zba_zbb_zbs -mno-strict-align -fno-stack-protector \
    -shared -fPIC \
    -I/work/native/include \
    /work/native/riscv64/lemire_native_riscv64.cpp \
    -o /work/build/liblemire_native_riscv64.so
"

# 将编译好的 RISC-V .so 拷回宿主机 build/ 目录留存
docker cp "${CONTAINER_NAME}:/work/build/liblemire_native_riscv64.so" "${ROOT_DIR}/build/liblemire_native_riscv64.so"

echo "==> Pre-benchmark Host CPU Load Check (top):"
top -l 2 -n 6 -o cpu | tail -n 16

echo "==> [2/2] Running JMH Benchmark under QEMU 10.2.1 (qemu-riscv64 -cpu max) + RISC-V OpenJDK 26..."
JMH_ARGS="${*:-bench.LemireFfmBenchmark -f 0 -wi 2 -w 1s -i 3 -r 1s}"
docker exec "${CONTAINER_NAME}" bash -c "
  cd /work
  qemu-riscv64 -cpu max \
    /usr/lib/jvm/java-26-openjdk-riscv64/bin/java \
    --enable-native-access=ALL-UNNAMED \
    -Dlemire.lib=/work/build/liblemire_native_riscv64.so \
    -cp 'build/classes:lib/*' \
    org.openjdk.jmh.Main ${JMH_ARGS}
"
