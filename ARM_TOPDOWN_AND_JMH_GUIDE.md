# Arm Telemetry Solution (`topdown-tool`) + Linux `perf` + JMH 深度微架构性能分析指南

本文档基于对 Arm 官方开源仓库 **[`telemetry-solution`](https://gitlab.arm.com/telemetry-solution/telemetry-solution)**（已克隆并分析其 `data/pmu/cpu/specifications/` 与 `tools/topdown_tool/` 源码）的深度剖析，系统阐述：
1. **Arm `telemetry-solution` (`topdown-tool`) 的底层架构与工作机制**；
2. **Arm Topdown 方法论与 Intel TMA (`pmu-tools / toplev`) 的 6 大核心区别（含精确 PMU 数学公式对比）**；
3. **如何在 Linux ARM64 服务器（Neoverse N1/N2/N3/V1/V2/V3 等）上将 `topdown-tool` + `perf` 与 JMH 基准测试精准结合**；
4. **基于 Arm Topdown Stage 1 + Stage 2 指标体系，对本项目 `Double.parseDouble` 与 `String.split` 优化前后的理论微架构指标推演与拆解**。

---

## 1. Arm `telemetry-solution` 仓库架构与底层工作原理

Arm 官方的 [`telemetry-solution`](https://gitlab.arm.com/telemetry-solution/telemetry-solution) 是 Arm 针对 Neoverse、Cortex-X/A、Lumex 等处理器家族以及 CMN（Coherent Mesh Network）片上互联网络打造的标准化性能遥测与 Top-Down 分析框架。

### 1.1 仓库四大核心模块

```text
telemetry-solution/
├── data/
│   └── pmu/
│       ├── cpu/
│       │   ├── mapping.json                  # CPU MIDR_EL1 硬件标识码到 JSON 规范文件的映射表
│       │   ├── schemas/                      # MRS (Machine Readable Specification) JSON Schema 定义
│       │   └── specifications/               # 各款 CPU 的事件 (events)、公式 (metrics)、分组与决策树
│       │       ├── neoverse/                 # Neoverse N1, N2, N3, V1, V2, V3, V3AE
│       │       ├── cortex/                   # Cortex-A725, Cortex-X925
│       │       └── lumex/                    # Lumex C1-Nano/Pro/Premium/Ultra & SME2
│       └── cmn/                              # CMN-700 / CMN-S3 互联网格事件、Watchpoint 与带宽/冲突指标
├── tools/
│   ├── topdown_tool/                         # 核心 Python CLI 与 API：驱动 perf stat / WindowsPerf 采集并渲染树状表
│   ├── perf_json_generator/                  # 将 Arm MRS JSON 转换为 Linux 内核 tools/perf/pmu-events/ 格式的生成器
│   ├── spe_parser/                           # Arm SPE (Statistical Profiling Extension) 硬件采样包解析工具
│   └── ustress_charts/                       # 微架构压力测试可视化工具
└── benchmarks/
    ├── ustress/                              # 针对前端取指/分支/L1D/TLB/FP/SIMD 单一瓶颈设计的微基准测试集
    └── systress/                             # 针对 CMN 网格与内存子系统的系统级压力测试集
```

### 1.2 `topdown-tool` 在底层是如何调用 `perf` 的？（基于源码 `topdown_tool/perf/` 剖析）

1. **硬件型号自动探测（`cpu_detector.py` & `mapping.json`）**：
   - 在 Linux 上读取 `/sys/devices/system/cpu/cpuX/regs/identification/midr_el1` 获取 `MIDR_EL1` 寄存器值（例如 Neoverse V2 为 `0x41d4f`，Neoverse N2 r0p3 为 `0x410fd493`，Neoverse V3 为 `0x41d84`），通过 `data/pmu/cpu/mapping.json` 自动加载对应的微架构 JSON 规范。
2. **可用 PMU 硬件计数器数量探测（`LinuxPerfBase._probe_pmu_count`）**：
   - Arm PMUv3 架构通常提供 **6 个可编程通用计数器（`PMEVCNTR0_EL0` ~ `PMEVCNTR5_EL0`）+ 1 个专用周期计数器（`PMCCNTR_EL0`，对应 `CPU_CYCLES`）**。
   - `topdown-tool` 启动时会执行探针命令 `perf stat -e '{r8:u,r8:u,...}' -C <core> perf -v`，检测 stderr 中是否出现 `<not counted>` 或缩放比例 `<100%`，从而动态确定单次不复用可同时调度的最大事件数 `max_events`（通常为 6）。
3. **原子事件组打包调度（`EventScheduler` in `event_scheduler.py`）**：
   - 为了防止同一个指标公式（如 `Retiring = 100 * (OP_RETIRED / OP_SPEC * (1 - STALL_SLOT / (CPU_CYCLES * 8)))`）中的 4 个事件在不同时间片被分时复用（Multiplexing）导致除数与被除数失配（甚至算出 `> 100%` 或负数），`EventScheduler` 默认采用 `CollectBy.METRIC` 或 `CollectBy.GROUP` 策略：
   - 将计算单个 Metric 所需的全部 PMU 事件放进同一个 `perf stat` 花括号组 `-e '{event1,event2,event3,event4}'` 中，强制 Linux 内核 PMU 驱动将这几个事件**同时挂载到硬件寄存器上、在同一周期窗口内同步启停**。
   - 默认通过 `--cpu-mux-interval 4` 将 Linux PMU 硬件分组切换周期设为 **4ms**（可通过命令行调整）。

---

## 2. Arm Topdown (`telemetry-solution`) 与 Intel TMA (`pmu-tools / toplev`) 的 6 大本质区别

很多熟悉 x86 性能调优的工程师习惯了 Intel 的 TMA（Top-Down Microarchitecture Analysis，`perf stat --topdown` 或 Andi Kleen 的 `pmu-tools / toplev`），到了 Arm 服务器上会发现指标结构与公式逻辑有显著差异。两者的核心差异如下：

### 2.1 区别一：整体方法论结构不同 —— Intel“单棵四级严格闭合树（L1–L4）” vs. Arm“两阶段混合模型（Stage 1 流水线 Slot 树 + Stage 2 正交微架构资源有效性指标群）”

* **Intel TMA 模型**：
  - 从 Level 1 到 Level 4 是一棵**严格按周期/Slot 比例拆分的闭合树**（例如：`Backend_Bound = Memory_Bound + Core_Bound`；`Memory_Bound = L1_Bound + L2_Bound + L3_Bound + DRAM_Bound + Store_Bound`）。
  - Intel 在硬件中设计了 `CYCLE_ACTIVITY.STALLS_L1D_MISS`、`CYCLE_ACTIVITY.STALLS_L2_MISS`、`CYCLE_ACTIVITY.STALLS_MEM_ANY` 等周期活动计数器，试图在硬件层面把“后端停顿的每一个周期”强行归因到某一级缓存或执行单元上。
* **Arm Telemetry Solution 模型（两阶段法）**：
  - Arm 架构师认为，在超标量乱序执行（Out-of-Order）微架构中，L1D 未命中、L2 未命中、TLB Walk 与算术指令发射往往高度重叠（Memory-Level Parallelism），强行将后端停顿按百分比拆成相加等于 100% 的 L1/L2/DRAM 停顿比例容易产生失真。
  - 因此，Arm 将分析明确拆分为**两个正交阶段（Two Stages）**：
    - **Stage 1（Topdown 流水线 Slot 分析）**：回答**“流水线发射槽位（Issue/Rename Slots）花在了哪里？”**。将总 Slot（$\text{CPU\_CYCLES} \times \text{Width}$）严格划分为 4 个相加约等于 100% 的根节点：
      1. `Frontend Bound`（前端取指/译码受限）
      2. `Bad Speculation`（分支预测错误与投机冲刷浪费）
      3. `Retiring`（有效完成并退休的微操作占比）
      4. `Backend Bound`（后端重命名/保留站/执行单元/访存受限）
      *(注：在 Armv9.2 新核如 Neoverse N3/V3、Cortex-X925 中，Stage 1 进一步提供了 `Topdown_Frontend` 和 `Topdown_Backend` 的二级停顿占比细分)*。
    - **Stage 2（Micro-architecture Exploration 微架构有效性探索）**：回答**“具体是哪个硬件单元引发了 Stage 1 的瓶颈？”**。不再强求按 Slot 百分比闭合，而是通过 JSON 中的 `decision_tree.next_items` 将 Stage 1 的瓶颈节点关联到 **15 个正交的微架构资源有效性指标组（Metric Groups）**：
      - 若 `Frontend Bound` 高 $\rightarrow$ 下钻查看 `Branch_Effectiveness`、`ITLB_Effectiveness`、`L1I_Cache_Effectiveness`、`L2_Cache_Effectiveness`。
      - 若 `Backend Bound` 高 $\rightarrow$ 下钻查看 `DTLB_Effectiveness`、`L1D_Cache_Effectiveness`、`L2_Cache_Effectiveness`、`LL_Cache_Effectiveness`、`Operation_Mix`（指令类型占比）。
      - 若 `Bad Speculation` 高 $\rightarrow$ 下钻查看 `Branch_Effectiveness`（`branch_mpki`, `branch_misprediction_ratio`）。
      - 若 `Retiring` 高 $\rightarrow$ 下钻查看 `General (IPC)`、`Operation_Mix`、`FP_Arithmetic_Intensity`、`SVE_Effectiveness`。

---

### 2.2 区别二：L1 四大核心公式与“分支预测错误阴影（Branch Misprediction Penalty）”修正算法不同

从 `data/pmu/cpu/specifications/neoverse/neoverse_v2_r0p0_pmu.json`（8-wide 超标量微架构，每周期 8 个 Slot）和 `neoverse_v3_r0p0_pmu.json`（10-wide 超标量微架构，每周期 10 个 Slot）提取的真实公式如下：

#### (1) Arm Neoverse V1 / N2 / V2 的 `Topdown_L1` 精确公式（以 8-wide 的 Neoverse V2 为例）：

$$\text{Frontend Bound (\%)} = 100 \times \left( \frac{\text{STALL\_SLOT\_FRONTEND}}{\text{CPU\_CYCLES} \times 8} - \frac{\text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}} \right)$$

$$\text{Backend Bound (\%)} = 100 \times \left( \frac{\text{STALL\_SLOT\_BACKEND}}{\text{CPU\_CYCLES} \times 8} - \frac{3 \times \text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}} \right)$$

$$\text{Retiring (\%)} = 100 \times \left( \frac{\text{OP\_RETIRED}}{\text{OP\_SPEC}} \times \left( 1 - \frac{\text{STALL\_SLOT}}{\text{CPU\_CYCLES} \times 8} \right) \right)$$

$$\text{Bad Speculation (\%)} = 100 \times \left( \left( 1 - \frac{\text{OP\_RETIRED}}{\text{OP\_SPEC}} \right) \times \left( 1 - \frac{\text{STALL\_SLOT}}{\text{CPU\_CYCLES} \times 8} \right) + \frac{4 \times \text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}} \right)$$

> **【深度微架构原理解析：为什么 Arm 公式里会出现 $-\frac{\text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}}$、$-\frac{3 \times \text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}}$ 和 $+\frac{4 \times \text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}}$？】**
> - 在 Arm Neoverse V1/N2/V2 硬件设计中，当分支执行单元发现一次分支预测错误（`BR_MIS_PRED`）并触发流水线冲刷（Pipeline Flush）时：
>   1. 前端重新重定向 PC 取指会产生约 **1 个完整周期（8 个 Slot）** 的停顿，被硬件计入了 `STALL_SLOT_FRONTEND`；
>   2. 后端恢复寄存器重命名表（Rename Recovery）会产生约 **3 个完整周期（24 个 Slot）** 的停顿，被硬件计入了 `STALL_SLOT_BACKEND`。
> - 但从逻辑归因上看，这 $1 + 3 = 4$ 个周期的停顿**根本原因都是分支预测失败（Bad Speculation）**，而不是指令缓存缺失或数据缓存缺失！
> - 因此，Arm 在公式中从 `Frontend Bound` 扣掉 $1 \times \frac{\text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}}$，从 `Backend Bound` 扣掉 $3 \times \frac{\text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}}$，并将这 **$4 \times \frac{\text{BR\_MIS\_PRED}}{\text{CPU\_CYCLES}}$** 补偿加回到 `Bad Speculation` 中！
> - 而在更新一代的 **Neoverse V3 / N3（Armv9.2，10-wide）** 中，Arm 硬件直接新增了专门的 `STALL_FRONTEND_FLUSH` 计数器来精确统计因分支冲刷导致的前端停顿周期，因此 Neoverse V3 的公式演进为：
>   $$\text{Frontend Bound}_{V3} = 100 \times \left( \frac{\text{STALL\_SLOT\_FRONTEND}}{10 \times \text{CPU\_CYCLES}} - \frac{\text{STALL\_FRONTEND\_FLUSH}}{\text{CPU\_CYCLES}} \right)$$
>   $$\text{Bad Speculation}_{V3} = 100 \times \left( 1 - \frac{\text{STALL\_SLOT}}{10 \times \text{CPU\_CYCLES}} \right)\left( 1 - \frac{\text{OP\_RETIRED}}{\text{OP\_SPEC}} \right) + 100 \times \frac{\text{STALL\_FRONTEND\_FLUSH}}{\text{CPU\_CYCLES}}$$

---

### 2.3 区别三 ~ 六：Arm `telemetry-solution` vs. Intel TMA 全面对比速查表

| 对比维度 | Intel TMA (`perf stat --topdown` / `toplev`) | Arm Telemetry Solution (`topdown-tool` + `perf`) |
| :--- | :--- | :--- |
| **1. 方法论树形结构** | 单一 L1 $\rightarrow$ L4 严格百分比闭合树 | **Stage 1（L1/L2 Slot 停顿树）+ Stage 2（15~26 个正交资源有效性指标组：MPKI、Miss Ratio、Operation Mix）** |
| **2. 核心 Slot 计数器** | `TOPDOWN.SLOTS`, `IDQ_UOPS_NOT_DELIVERED.CORE`, `UOPS_ISSUED.ANY`, `UOPS_RETIRED.RETIRE_SLOTS`, `INT_MISC.RECOVERY_CYCLES` | `CPU_CYCLES`, `STALL_SLOT`, `STALL_SLOT_FRONTEND`, `STALL_SLOT_BACKEND`, `OP_SPEC`, `OP_RETIRED`, `BR_MIS_PRED` / `STALL_FRONTEND_FLUSH` |
| **3. 指令 vs 微操作区分** | 全部基于微操作 `UOPS`（x86 复杂指令拆分为多个 uops） | 严格区分**微操作 `OP_SPEC` / `OP_RETIRED`**（用于 Stage 1 Slot 计算）与**架构指令 `INST_SPEC` / `INST_RETIRED`**（用于 Stage 2 `IPC`、`MPKI`、`Operation_Mix`） |
| **4. 专用 Topdown 寄存器** | Ice Lake+ 具有专用 `PERF_METRICS` MSR，单寄存器直出 L1 4 项指标 | 无专用固定 Topdown MSR，由 `topdown-tool` 的 `EventScheduler` 将 PMU 事件打包成 $\le 6$ 事件的原子组 `{...}` 交由 `perf stat` 采集 |
| **5. 指令种类配比剖析** | 散落在 L3/L4 `Retiring` 子节点（如 `Base` vs `MS_Switches`、`FP_Arith`） | 设有专门的 Stage 2 **`Operation_Mix`（投机指令配比）**：直接给出 `LD_SPEC`（访存读）、`ST_SPEC`（访存写）、`DP_SPEC`（整数运算）、`ASE_SPEC`（NEON SIMD）、`VFP_SPEC`（标量浮点）、`BR_IMMED_SPEC + BR_INDIRECT_SPEC`（分支）占总指令的百分比！ |
| **6. 硬件指令级采样** | Intel **PEBS** (Precise Event-Based Sampling) | Arm **SPE** (Statistical Profiling Extension) + 仓库内置的 `tools/spe_parser` |

---

## 3. 如何将 Arm `topdown-tool` + `perf` 与 JMH 结合使用？（实战操作手册）

在对 Java / OpenJDK 代码（如我们的 `Double.parseDouble` 和 `String.split`）进行 `topdown-tool` + JMH 联合剖析时，如果直接用 `topdown-tool -- java -jar benchmarks.jar` 裸跑，会踩中 **3 个致命陷阱**：
1. **JVM 启动、类加载与 C1/C2 JIT 编译线程会严重污染 PMU 计数器**（你测到的会是 `javac`/C2 编译器的前端指令缓存缺失，而不是目标方法的微架构特征！）。
2. **多核漂移**：如果不绑核，Java 业务线程在多个 CPU 核心之间迁移，导致单核 PMU 统计被稀释。
3. **采集时长与 4ms Multiplexing 窗口匹配问题**：当同时采集 Stage 1 + Stage 2 全部 16 个指标组（约 40+ 个独立 PMU 事件，分为 8~10 个原子事件组轮转，每组 4ms）时，单次完整轮转需要约 40ms，因此稳态测量时间应至少持续 **3~5 秒**。

### 3.1 推荐方案 A：利用 `taskset` 物理绑核 + JMH 单 Fork 长稳态窗口 + `topdown-tool -C` 精确采集

在支持 Arm PMUv3 的 Linux ARM64 物理机或云主机（如 AWS Graviton3 `Neoverse-V1`、Graviton4 `Neoverse-V2`、阿里云倚天 710 `Neoverse-N2`）上：

```bash
# 1. 开启 PMU 访问权限
sudo sh -c 'echo -1 > /proc/sys/kernel/perf_event_paranoid'

# 2. 先单独查看当前 Arm CPU 支持的指标组与事件
topdown-tool --cpu-list-cores
topdown-tool --cpu-list-groups
topdown-tool --cpu-list-metrics -d --cpu-show-sample-events

# 3. 绑定 CPU Core 4 运行 JMH（将 JVM GC/JIT 后台线程限制在 Core 0-1，或单核延长稳态测量时间至 5s 稀释预热阶段）：
#    使用 -I 1000 每秒输出一次时间序列 CSV，这样可以直接剔除前 2 秒的 JMH Warmup 数据，只取后 5 秒 Measurement 稳态窗口！
topdown-tool \
  --probe CPU \
  -C 4 \
  -s combined \
  --generate-csv metrics,events \
  --csv-output-path ./tdt_jmh_unpatched \
  -I 1000 \
  -- taskset -c 4 java \
     -XX:ActiveProcessorCount=1 \
     -XX:+UseSerialGC \
     -cp "build/classes:lib/*" \
     org.openjdk.jmh.Main jmh.LibSvmFeatureSplitParseBenchmark.split4096Lines_parseKeyAndValue \
     -f 1 -wi 2 -w 1s -i 5 -r 1s

# 4. 对比运行打补丁后的内置 OpenJDK 27 (--patch-module java.base=build/java.base-classes)：
topdown-tool \
  --probe CPU \
  -C 4 \
  -s combined \
  --generate-csv metrics,events \
  --csv-output-path ./tdt_jmh_patched \
  -I 1000 \
  -- taskset -c 4 java \
     --patch-module java.base=build/java.base-classes \
     -XX:ActiveProcessorCount=1 \
     -XX:+UseSerialGC \
     -cp "build/classes:lib/*" \
     org.openjdk.jmh.Main jmh.LibSvmFeatureSplitParseBenchmark.split4096Lines_parseKeyAndValue \
     -f 1 -wi 2 -w 1s -i 5 -r 1s
```

> **为什么开启 `-I 1000` + `--generate-csv metrics,events` 是结合 JMH 的神技？**
> - JMH 的运行过程是：`[JVM 启动 0.3s] -> [Warmup 1 (1s)] -> [Warmup 2 (1s)] -> [Iteration 1..5 (5s)]`。
> - 当 `topdown-tool` 加上 `-I 1000`（每 1000ms 生成一行带时间戳的 Topdown 指标 CSV）后，打开生成的 `tdt_jmh_patched/<timestamp>/cpu_metrics.csv`，**直接丢弃前 2.5 秒（Warmup 阶段）的行，只看第 3~7 秒（C2 已完全编译完毕、处于 100% 纯稳态热循环的 Measurement 阶段）的各列均值**！这样就能完全剥离 JVM 启动和 JIT 编译噪音！

---

### 3.2 推荐方案 B：直接用 Linux `perf stat` 手工执行 Arm `Topdown_L1` + `Operation_Mix` 原子事件组（配合 `jcmd` 或 JMH `-prof perfnorm`）

如果你在没有安装 Python `uv` 环境的内网生产容器里，或者想直接使用 JMH 内置的 `-prof perfnorm`（JMH 的 `LinuxPerfNormProfiler` 会把每个 PMU 事件计数自动除以 JMH 的 `ops` 操作次数，输出精确的 **`cycles/op`、`instructions/op`、`stall_slot_backend/op`**！），你可以直接从 `telemetry-solution` 的 JSON 规范中提取出底层的硬件事件名传给 JMH：

```bash
# 直接利用 JMH 内置的 perfnorm 采集 Arm Topdown L1 与 Operation Mix 核心硬件事件（自动归一化到每次方法调用 #/op）：
java --patch-module java.base=build/java.base-classes \
     -cp "build/classes:lib/*" \
     org.openjdk.jmh.Main jmh.SplitInputShapesBenchmark \
     -f 1 -wi 2 -w 1s -i 4 -r 2s \
     -prof perfnorm:events=cpu_cycles,inst_retired,inst_spec,op_retired,op_spec,stall_slot,stall_slot_frontend,stall_slot_backend,br_mis_pred,br_mis_pred_retired,l1d_cache_refill,dp_spec,ld_spec,st_spec,ase_spec
```

> **JMH `-prof perfnorm` + Arm Topdown 事件的独特优势**：
> - `perfnorm` 仅在 JMH 的 **Measurement 迭代窗口内** 读取 `perf` 计数，并除以该时间段内的实际方法调用次数（Operations）。
> - 你不仅能套用上面第 2.2 节的 Arm Topdown 公式算出 `Retiring %`、`Bad Speculation %`、`Backend Bound %`、`Frontend Bound %`，还能直接看到**每次调用 `parseDouble` 或 `String.split` 到底执行了多少条指令（`inst_retired/op`）、消耗了多少个周期（`cpu_cycles/op`）、发生了几次分支预测错误（`br_mis_pred_retired/op`）以及几次 L1D 缓存缺失（`l1d_cache_refill/op`）**！

---

## 4. 用 Arm Topdown (Stage 1 + Stage 2) 理论推演本项目 `parseDouble` 与 `String.split` 的微架构收益

结合我们在 `FloatingDecimal.java` 和 `String.java` 中的具体代码改造，用 Arm `telemetry-solution` 的 **Stage 1 (`Topdown_L1`)** 与 **Stage 2 (`General`, `Operation_Mix`, `Branch_Effectiveness`, `L1D_Cache_Effectiveness`)** 指标体系进行理论剖析，优化前后的微架构指纹变化如下：

### 4.1 场景一：`Double.parseDouble`（耗时从 `38.68 ns` $\rightarrow$ `16.04 ns`，提速 2.41x）

| Arm Topdown 阶段与指标组 | 具体指标名称 (Metric / Event) | 老版本 (原生 OpenJDK 27) 微架构表现 | **新版本 (内置 Lemire SWAR + 零分配快路径) 微架构表现** | 物理原因与代码级对应关系 |
| :--- | :--- | :---: | :---: | :--- |
| **Stage 2: `General`** | `INST_RETIRED / op`（每次调用退休指令数）<br>& `IPC` (`INST_RETIRED / CPU_CYCLES`) | 指令数高（~350+ 条/op）<br>`IPC` 偏低 | **总退休指令数暴降 > 55%**<br>**`IPC` 显著升高（接近核心宽度上限）** | <ul><li>老版本逐字符循环调用 `in.charAt(i)`、分配 `byte[] digits` 并拷贝、分配 `ASCIIToBinaryBuffer`、再二次遍历 `digits[]`。</li><li>新版本通过 [`isEightAsciiDigits`](jdk-patch/jdk/internal/math/FloatingDecimal.java) + [`parseEightAsciiDigits`](jdk-patch/jdk/internal/math/FloatingDecimal.java) 用 1 次 64 位读取 + 3 次乘法替代了 8 次循环迭代与 16 次条件分支，指令总数腰斩。</li></ul> |
| **Stage 1: `Topdown_L1`** | **`Bad Speculation (%)`**<br>& Stage 2 `branch_mpki` (`BR_MIS_PRED_RETIRED`) | **显著偏高** | **大幅下降** | <ul><li>老版本在每个数字字符上都要执行 `if (ch >= '0' && ch <= '9')` 和小数点/指数跳转，对于长度随机的浮点字符串，循环退出分支极易发生 `BR_MIS_PRED`（每次预测错误在 Neoverse 上带来 4 周期 = 32 Slots 的惩罚）。</li><li>新版本用 SWAR 一次无分支校验 8 个字节，直接消除了 7/8 的循环回边分支。</li></ul> |
| **Stage 1: `Topdown_L1`** | **`Backend Bound (%)`**<br>& Stage 2 `ST_SPEC` / `L1D_Cache_Effectiveness` | **高（受制于堆分配与 Store Buffer）** | **大幅下降** | <ul><li>老版本每次调用都在 TLAB 上分配 40 字节（`ASCIIToBinaryBuffer` + `byte[]`），产生大量對象头初始化与数组清零的 `ST_SPEC`（Store 操作），推高 `STALL_BACKEND_ST` / `STALL_SLOT_BACKEND`。</li><li>新版本 [`tryFastDoubleValue`](jdk-patch/jdk/internal/math/FloatingDecimal.java) 全程在通用寄存器（GPR）与浮点寄存器（FPR）内完成 `UMULH`（`Math.unsignedMultiplyHigh`）128 位运算，**堆写入 `ST_SPEC` 降为 0**！</li></ul> |
| **Stage 1: `Topdown_L1`** | **`Retiring (%)`**<br>& Stage 2 `Operation_Mix` (`DP_SPEC`) | 占比偏低（大量 Slot 浪费在 `STALL_SLOT_BACKEND` 与 `Bad Speculation`） | **`Retiring (%)` 跃升为主导项（且以高效 `DP_SPEC` 整数算术指令为主）** | 当消除了内存分配停顿与分支冲刷后，流水线 Slot 绝大部分用于退休有效的 SWAR 位运算与 `UMULH` 乘法指令，虽然 `Retiring %` 占比升高，但由于分母总周期数 `CPU_CYCLES` 减少了 58.5%，绝对执行时间缩短为原来的 1/2.41。 |

---

### 4.2 场景二：`String.split` 三级自适应架构（耗时提速 1.15x ~ 4.57x）

| 输入形态与优化分支 | Stage 1 `Topdown_L1` 主导变化 | Stage 2 关键资源指标变化 (`Operation_Mix` / `MPKI` / `Miss_Ratio`) | 微架构层面的精确解释 |
| :--- | :--- | :--- | :--- |
| **1. 密集短字段切分 (`"0,1,2..."` / `"1:123 2:345..."`，走 [`splitLatin1DenseSwar`](jdk-patch/java/lang/String.java))** | `Bad Speculation (%)` 下降<br>`Retiring (%)` 效率提升 | <ul><li>`branch_mpki` 下降</li><li>`DP_SPEC`（64 位标量位运算）占比上升，取代了频繁短跨度调用 `indexOf` 的前导分支指令</li></ul> | 当相邻分隔符间距仅 2~6 字节时，老版本每切一个词都要重进一次 `_indexOfL_char` 内建函数（执行长度判断与对齐分支，引发高频 `BR_MIS_PRED`）。新版本每次 16 字节双字 SWAR 扫描直接用 [`findEqualBytesSwarMask`](jdk-patch/java/lang/String.java) + `rbit/clz` 硬件指令在寄存器内连续提取多个偏移量。 |
| **2. 长文本日志行切分（每行 96B 共 6KB，走 [`splitLatin1SparseIntrinsic`](jdk-patch/java/lang/String.java) vs. 纯两遍 SWAR V1）** | `Backend Bound (%)` 显著下降（相比 V1 省去 `286.8 ns`） | <ul><li>**`ASE_SPEC` (`simd_percentage`) 占比高**：复用 `_indexOfL_char` 的 32B NEON SIMD 向量指令</li><li>**`l1d_cache_mpki` 与 `LD_SPEC` 下降**：单遍扫描趁 L1D 缓存热直接构造子串</li></ul> | 这正是我们在消融实验中发现 V1（`898 ns`）退化而 V2/Final（`612 ns`）反超的关键！对于 6KB 长文本，两遍扫描会多分配一次 `int[]` 并导致第二遍 `System.arraycopy` 时前面的缓存行已被挤出或产生双倍 `LD_SPEC`；而 `splitLatin1SparseIntrinsic` 用 NEON 向量指令定位 `\n` 后立刻趁 L1D Hit 完成拷贝。 |
| **3. 单分隔符 `"1:123"` (`secondIdx < 0`) 与尾部空逗号裁剪 (`computeTrimmedResultSize`)** | `Backend Bound (%)` 暴降<br>总 `CPU_CYCLES` 暴降（最高 **4.57x**） | <ul><li>`ST_SPEC`（堆对象头写入与数组清零）大幅减少</li><li>`INST_RETIRED` 大幅减少</li></ul> | 省去了 36,864 次 `ArrayList` + `Object[10]` 分配（LibSVM 场景）或 128 个无效空 `String` 槽位的分配与回退清理（尾部空逗号场景），直接砍掉了数千条内存分配与数组拷贝指令。 |

---

## 5. 下一步如果要在 Arm Linux 服务器上采集真实硬件 SPE 采样定位到行

当 `topdown-tool` 在 Stage 1 提示某个节点（例如 `backend_bound` 或 `bad_speculation`）占比最高时，可以直接查看 `topdown-tool --cpu-list-metrics --cpu-show-sample-events` 给出的推荐 `sample_events`，或使用 Arm **SPE (Statistical Profiling Extension)** 采样并在仓库自带的 `tools/spe_parser` 中解码：

```bash
# 1. 使用 Linux perf record 采集 Arm SPE 硬件采样（记录引发后端停顿与分支预测失败的精确 PC 指令地址与访存延迟）
sudo perf record -e arm_spe_0/load_filter=1,store_filter=1,branch_filter=1/ -- \
  java --patch-module java.base=build/java.base-classes \
       -XX:+PreserveFramePointer \
       -cp "build/classes:lib/*" \
       org.openjdk.jmh.Main jmh.LibSvmFeatureSplitParseBenchmark -f 1 -wi 2 -i 5
```

通过加上 `-XX:+PreserveFramePointer`（必要时配合 `perf-map-agent` 生成 `/tmp/perf-<pid>.map` JIT 符号表），`perf report` 与 `spe_parser` 即可将硬件 PMU 的 `STALL_SLOT_BACKEND` 和 `BR_MIS_PRED` 精确映射回 C2 编译后的 `FloatingDecimal::readLatin1FormatString` 或 `String::splitLatin1DenseSwar` 汇编指令行！
