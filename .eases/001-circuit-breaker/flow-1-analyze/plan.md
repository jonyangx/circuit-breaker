# 实施计划：Circuit Breaker（纳秒级无锁流量治理组件）

**分支**：`001-circuit-breaker` | **日期**：2026-07-30 | **规范**：`spec.md`
**输入**：`.eases/001-circuit-breaker/flow-1-analyze/spec.md`、`docs/brd/design.md`、`memory/constitution.md`
**说明**：Phase 1 技术计划，桥接 spec → tasks；架构/详细设计与框架骨架由 Phase 2（`flow-2-design`）承接。

## 摘要

构建一个嵌入式流量治理库：以**状态压缩 + 惰性计算 + 无锁 CAS + 配置/状态分离**实现纳秒级、零分配、无治理侧定时线程的 acquire/release 决策，覆盖熔断、限流、并发控制、系统过载四类能力，附 reactive 适配模块与 Prometheus exporter。

## 技术背景

**语言/版本**：Java 21+（LTS；用到虚拟线程相关推理、`@Contended`、`ThreadLocalRandom`）
**构建工具**：Gradle（Kotlin DSL）；JMH 插件做微基准（`me.champeck.jmh` 或等价）
**主要依赖**：JDK 仅含（核心库零三方依赖原则）；reactive 模块依赖 `io.projectreactor:reactor-core`；observability 模块依赖 `io.prometheus:simpleclient`
**存储**：N/A（内存级嵌入式库，无数据库）
**测试**：JUnit 5 + AssertJ；覆盖率 JaCoCo（行≥80%/分支≥70%/方法≥85%）；JMH 作性能回归
**目标平台**：JVM 服务端（Linux 为主，跨平台）
**项目类型**：single（多模块库，无 web/mobile）
**性能目标**：tryAcquire P50<100ns、release P50<50ns、热路径 0 字节分配（不可逾越回归红线，SC-001/002）
**约束条件**：无锁（单 AtomicLong CAS）、无治理侧后台定时线程、零堆分配（热路径）、release 不依赖线程
**规模/范围**：核心库 + reactive + observability + benchmarks 四模块；v1 排除集群限流/热点参数/配置中心监听器

## 宪章检查

*关卡：第 0 阶段研究前必须通过。*

| 宪章原则/不变量 | 检查结果 | 说明 |
|----------------|---------|------|
| III. Test-First（NON-NEGOTIABLE） | ✅ 通过 | tasks.md 全程 TDD 红-绿-重构，先写失败测试 |
| I. Library-First | ✅ 通过 | 自包含可独立测试库；CLI 原则 II 已豁免 |
| II. CLI Interface | ✅ 豁免 | 嵌入式治理 SDK 无 CLI 语义（constitution 已记录豁免） |
| V. Observability | ✅ 通过 | LongAdder 单调计数 + EWMA Gauge（US7） |
| VIII. Relative Paths | ✅ 通过 | 所有产出用项目相对路径 |
| 不变量1 性能预算 | ✅ 通过 | JMH 基准 + 性能门控（SC-001/002） |
| 不变量2 零分配 | ✅ 通过 | 热路径 64 位 long token，禁对象 new |
| 不变量3 无锁 | ✅ 通过 | 单 AtomicLong CAS；可分段仅限可交换求和量 |
| 不变量4 无治理定时线程 | ✅ 通过 | 惰性推导；仅观测/系统探针低频后台且非热路径 |
| 不变量5 配置-状态分离 | ✅ 通过 | `CONFIGS`/`STATES` 双槽（FR-002/BR-002） |
| 不变量6 自描述 token | ✅ 通过 | token 携 version+bucketIdx，release 线程无关（FR-010/BR-060） |

**评估**：无未获正当理由的违规，关卡通过。bit-packing/lock-free/lazy 等复杂手段均有性能不变量正当理由（constitution 原则 VII 特例）。

## 项目结构

### 文档（本功能）

```text
.eases/001-circuit-breaker/
└── flow-1-analyze/
    ├── spec.md            # 需求规范（specify）
    ├── plan.md            # 本文件（plan）
    ├── tasks.md           # 任务清单（tasks）
    └── checklists/
        └── requirements.md # 规范质量清单
```
（架构/详细设计/框架骨架在 Phase 2 的 `flow-2-design/` 产出）

### 源码（仓库根目录）— Gradle 多模块

```text
settings.gradle.kts
build.gradle.kts                # 根构建（JaCoCo 聚合、公共配置）
gradle/libs.versions.toml       # 版本目录

circuit-breaker-core/           # 核心 SDK（零三方依赖）
  src/main/java/dev/circuitbreaker/core/
    TokenCodec.java             # 64 位 token encode/decode（BR-003）
    ClockSource.java            # nanoTime 相对单调时钟（BR-006）
    ResourceConfig.java         # 不可变配置（BR-002/050）
    ResourceState.java          # 聚合根：bucketState/breakerState/ewmaState/concurrency[]/LongAdder
    ResourceManager.java        # register → resourceId（UC-001）
    FlatExecutionEngine.java    # tryAcquire/release + bitmask 分派（UC-002/003）
    BlockCode.java              # -1/-2/-3/-4（BR-004）
    ratelimit/LazyTokenBucket.java        # 惰性令牌桶（UC-004）
    breaker/EwmaCircuitBreaker.java       # EWMA 三态状态机（UC-005）
    breaker/EwmaAlpha.java                # α 分段近似 LUT（BR-021）
    concurrency/SegmentedConcurrency.java # 分段并发（UC-006）
    system/SystemOverload.java            # 分级丢弃 + 迟滞（UC-007）
    reload/ConfigSwapper.java             # RCU 热换（UC-008）
  src/test/java/dev/circuitbreaker/core/...

circuit-breaker-reactive/       # Reactor/WebFlux 适配（依赖 reactor-core）
  src/main/java/dev/circuitbreaker/reactive/CircuitBreakerOperator.java

circuit-breaker-observability/  # Prometheus exporter（依赖 simpleclient）
  src/main/java/dev/circuitbreaker/observability/CircuitBreakerCollector.java

circuit-breaker-benchmarks/     # JMH 微基准（验证 SC-001/002）
  src/jmh/java/dev/circuitbreaker/benchmarks/AcquireReleaseBenchmark.java
```

**结构决策**：多模块库——`-core` 零三方依赖保证可被任何中间件内嵌；`-reactive`/`-observability` 为可选依赖；`-benchmarks` 独立校验性能红线。公共构建配置上提至根 `build.gradle.kts`，版本集中 `libs.versions.toml`。

## 复杂度记录

> 性能不变量驱动的复杂度，记录正当理由（constitution 原则 VII 特例）。

| 复杂手段 | 原因 | 更简单方案被拒绝的理由 |
|----------|------|------------------------|
| 64 位 token bit-packing | 零分配 + release 线程无关 | 返回对象（Entry/Context）会触发 GC 且需 ThreadLocal 绑定（破坏无分配/reactive） |
| 单 AtomicLong CAS 状态机 | 无锁 + 纳秒开销 | `synchronized`/`ReentrantLock` 阻塞与调度开销远超预算 |
| 惰性时间推导 | 无治理侧定时线程 | 后台定时线程打破不变量4，且引入额外调度/CPU |
| 时间衰减 EWMA + α 分段近似 | 无 LeapArray 内存 + 无 per-request `Math.exp` | 滑动窗口占用大量 MetricBucket/数组；`Math.exp` ~20-40ns 已与整条路径同量级 |
| 配置/状态分离 | 消除在途 release 错乱（v1 根因缺陷） | 整体替换含状态对象致计数漂移/并发变负/僵尸限流 |
| 代际（generation） | 无锁消除 ABA + 陈旧错误率 | 显式清零 CAS 存在竞态窗口；跨 AtomicLong 复合原子操作无锁不可达 |

## 阶段交付（与 tasks.md 对应）

1. **搭建（Phase1 of tasks）**：Gradle 多模块骨架 + 版本目录 + JaCoCo + JMH。
2. **基础（Foundation）**：TokenCodec、ClockSource、ResourceConfig/State、CONFIGS/STATES、FlatExecutionEngine 骨架 + bitmask 分派——所有用户故事依赖。
3. **US1 MVP**：LazyTokenBucket + 限流 acquire/release（可独立交付的限流器）。
4. **US2**：EwmaCircuitBreaker + EwmaAlpha LUT（三态 + 代际）。
5. **US3**：SegmentedConcurrency（分段 + TLR probe）。
6. **US4**：SystemOverload（分级 + 迟滞 + 探针）。
7. **US5**：ConfigSwapper（RCU + 版本校验）。
8. **US6**：reactive 模块（CircuitBreakerOperator）。
9. **US7**：observability 模块（Prometheus collector）。
10. **打磨**：JMH 性能门控回归、覆盖率达标、跨线程/热更新集成测试、文档。

## 备注

- 详细类设计/接口契约/方法签名在 Phase 2 `flow-2-design` 产出（`arch-design.md`/`detail-design.md`/`api-interface-report.md`/框架骨架）。
- 本 plan 不生成代码，仅规划；Phase 3 `flow-3-implement` 消费本目录 tasks.md（TDD）。
