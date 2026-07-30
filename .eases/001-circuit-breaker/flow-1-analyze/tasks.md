# 任务：Circuit Breaker（纳秒级无锁流量治理组件）

**输入**：`.eases/001-circuit-breaker/flow-1-analyze/` 的 `plan.md`（必需）、`spec.md`（用户故事，必需）、`docs/domains/001-circuit-breaker/`（UC/BR）
**前置条件**：`plan.md`、`spec.md` 已就绪
**测试**：Test-First 为宪章 NON-NEGOTIABLE（constitution 原则 III）——每个故事先写失败测试，再实现。
**组织方式**：按用户故事分组，每个故事可独立实施与测试（MVP = US1）。
**回写规则**：flow-3 仅就地把完成项 `[ ]` → `[X]`，不重跑 specify/plan/tasks。

## 格式：`[ID] [P?] [Story] 描述`

- **[P]**：可并行（不同文件、无依赖）
- **[Story]**：所属用户故事
- 描述含精确文件路径

## 第 1 阶段：搭建（共享基础设施）

**目的**：Gradle 多模块骨架与工具链

- [X] T001 创建根 `settings.gradle.kts`，包含 core/reactive/observability/benchmarks 四模块
- [X] T002 创建根 `build.gradle.kts`（Java 21 工具链、JaCoCo 聚合、公共配置）
- [X] T003 [P] 创建 `gradle/libs.versions.toml`（junit、assertj、reactor-core、prometheus、jmh 版本）
- [X] T004 [P] 配置 `circuit-breaker-benchmarks` 的 JMH 插件与 jmh 源集

**检查点**：`./gradlew build` 通过（空模块可编译）

---

## 第 2 阶段：基础（阻塞性前置——所有用户故事依赖）

**目的**：资源寻址 + 64 位 token + 时钟 + 引擎骨架（共享内核）
**⚠️ 关键**：本阶段完成前不得开展任何用户故事

- [X] T005 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/TokenCodecTest.java` 编写 token encode/decode 测试（位布局、符号位恒0、RT 模减法、version/bucketIdx/mask 往返）——须先失败
- [X] T006 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/TokenCodec.java` 实现 encode/decode（BR-003）使 T005 通过
- [X] T007 [P] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/BlockCode.java` 定义阻断码常量 -1/-2/-3/-4（BR-004）
- [X] T008 [P] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/ClockSource.java` 实现 `nanoTime/1M - START` 相对单调时钟（BR-006）+ 测试
- [X] T009 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/ResourceConfig.java` 定义不可变配置（含 version）（BR-002/050）
- [X] T010 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/ResourceState.java` 定义聚合根（bucketState/breakerState/ewmaState AtomicLong + concurrency[] AtomicInteger + pass/block LongAdder，@Contended 填充）
- [X] T011 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/ResourceManager.java` 实现 register → resourceId + `CONFIGS[]`/`STATES[]`（BR-001，UC-001）+ 测试
- [X] T012 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/FlatExecutionEngine.java` 实现 tryAcquire/release 骨架 + bitmask 分派（BR-005，UC-002/003）——模块接入点留 TODO，配跨阶段测试

**检查点**：基础完备——四类能力模块可并行接入

---

## 第 3 阶段：用户故事 1 - 令牌桶限流（优先级：P1）🎯 MVP

**目标**：注册资源 + 限流 acquire/release，交付可用限流器
**独立测试**：rate=1000/ms 资源以 2000/ms 调用，断言约半数 `-3`、令牌不超额；零分配 JMH

### 用户故事 1 的测试（Test-First）
- [X] T013 [P] [US1] 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/ratelimit/LazyTokenBucketTest.java` 编写限流判定测试（超额阻断、补令牌、低 QPS 抹零 BR-013）——须先失败
- [X] T014 [P] [US1] 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/FlatExecutionEngineRateLimitTest.java` 编写 acquire/release 限流端到端测试

### 用户故事 1 的实现
- [X] T015 [US1] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/ratelimit/LazyTokenBucket.java` 实现惰性令牌桶（位布局 BR-010、惰性补 BR-011、不分段 BR-012、抹零 BR-013）
- [X] T016 [US1] 在 `FlatExecutionEngine.java` 接入 0x02 限流分派（返回 -3）
- [X] T017 [US1] 在 `FlatExecutionEngine.release` 接入限流 release 路径 + pass/block 计数

**检查点**：US1 可完整运行并独立测试（限流器 MVP 可用）

---

## 第 4 阶段：用户故事 2 - EWMA 熔断（优先级：P1）

**目标**：时间衰减 EWMA + 三态状态机 + 代际
**独立测试**：连续失败→OPEN→到期→HALF_OPEN→探路成功→CLOSED，不被旧值二次跳闸

### 用户故事 2 的测试
- [X] T018 [P] [US2] 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/breaker/EwmaAlphaTest.java` 编写 α 分段近似测试（u≤1/128 α≈u、插值误差 ppm 级、u≥8 α=1）——须先失败
- [X] T019 [P] [US2] 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/breaker/EwmaCircuitBreakerTest.java` 编写三态迁移 + 代际重播种测试（BR-020/024/025）

### 用户故事 2 的实现
- [X] T020 [P] [US2] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/breaker/EwmaAlpha.java` 实现 α LUT + 分段近似（无 Math.exp，BR-021）
- [X] T021 [US2] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/breaker/EwmaCircuitBreaker.java` 实现 ewmaState/breakerState 位编解码、三态迁移 transition()、代际 updateEwma（BR-022/023/024/025）
- [X] T022 [US2] 在 `FlatExecutionEngine.java` 接入 0x01 熔断分派（acquire 判定 + release 上报/迁移，返回 -2）

**检查点**：US2 熔断三态闭环可独立测试

---

## 第 5 阶段：用户故事 3 - 分段并发控制（优先级：P2）

**目标**：分段近似并发 + TLR probe 路由 + bucketIdx 回滚
**独立测试**：并发上限=10，20 并发 acquire 断言约 10 放行；跨线程 release 求和归零

### 用户故事 3 的测试
- [X] T023 [P] [US3] 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/concurrency/SegmentedConcurrencyTest.java` 编写分段并发测试（上限阻断、跨线程回滚零漂移）——须先失败

### 用户故事 3 的实现
- [X] T024 [US3] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/concurrency/SegmentedConcurrency.java` 实现分段 AtomicInteger + TLR probe 路由（BR-030/031/032）
- [X] T025 [US3] 在 `FlatExecutionEngine.java` 接入 0x04 并发分派（acquire 写 bucketIdx、release 按 bucketIdx 回滚，返回 -4）

**检查点**：US3 并发控制可独立测试

---

## 第 6 阶段：用户故事 4 - 系统过载分级丢弃（优先级：P2）

**目标**：SHED_PERMILLE 分级概率 + 迟滞 + 低频探针
**独立测试**：探针写 SHED_PERMILLE=500 断言约 50% 顶层 -1；迟滞升降档无抖动

### 用户故事 4 的测试
- [X] T026 [P] [US4] 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/system/SystemOverloadTest.java` 编写分级丢弃 + 迟滞测试（BR-040/041）——须先失败

### 用户故事 4 的实现
- [X] T027 [US4] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/system/SystemOverload.java` 实现前置概率短路 + 迟滞档位 + 低频 CPU 探针（BR-040/041/042）
- [X] T028 [US4] 在 `FlatExecutionEngine.tryAcquire` 入口接入系统过载前置短路（返回 -1）

**检查点**：US4 过载保护可独立测试

---

## 第 7 阶段：用户故事 5 - RCU 热更新（优先级：P3）

**目标**：配置原子指针替换 + 状态稳定 + release 版本校验
**独立测试**：热换 rate 生效；acquire/release 间热换，并发求和归零、无负值

### 用户故事 5 的测试
- [X] T029 [P] [US5] 在 `circuit-breaker-core/src/test/java/dev/circuitbreaker/core/reload/ConfigSwapperTest.java` 编写 RCU 热换 + 在途 release 版本校验测试（BR-050/051/052）——须先失败

### 用户故事 5 的实现
- [X] T030 [US5] 在 `circuit-breaker-core/src/main/java/dev/circuitbreaker/core/reload/ConfigSwapper.java` 实现 CONFIGS.set 原子替换（version+1，STATES 不动）
- [X] T031 [US5] 在 `FlatExecutionEngine.release` 接入 token.version 校验（不匹配时并发照常回滚、EWMA 降权/跳过）

**检查点**：US5 热更新正确性可独立测试

---

## 第 8 阶段：用户故事 6 - 响应式适配（优先级：P3）

**目标**：Reactor 操作符，跨线程 acquire/release 安全
**独立测试**：Mono.defer acquire + doOnSuccess/doOnError release（不同线程），并发求和归零、EWMA 正确

### 用户故事 6 的测试
- [X] T032 [P] [US6] 在 `circuit-breaker-reactive/src/test/java/dev/circuitbreaker/reactive/CircuitBreakerOperatorTest.java` 编写跨线程响应式治理测试（BR-060/061）——须先失败

### 用户故事 6 的实现
- [X] T033 [US6] 在 `circuit-breaker-reactive/src/main/java/dev/circuitbreaker/reactive/CircuitBreakerOperator.java` 实现 Reactor 操作符/便捷包装（无 ThreadLocal）

**检查点**：US6 reactive 模块可独立测试

---

## 第 9 阶段：用户故事 7 - Prometheus 导出（优先级：P3）

**目标**：pass/block counter + EWMA Gauge exporter
**独立测试**：scrape 断言 counter 单调、EWMA Gauge 收敛、无 reset

### 用户故事 7 的测试
- [X] T034 [P] [US7] 在 `circuit-breaker-observability/src/test/java/dev/circuitbreaker/observability/CircuitBreakerCollectorTest.java` 编写导出测试（单调、Gauge、禁 reset，BR-070/071/072）——须先失败

### 用户故事 7 的实现
- [X] T035 [US7] 在 `circuit-breaker-observability/src/main/java/dev/circuitbreaker/observability/CircuitBreakerCollector.java` 实现 Prometheus collector（只读 sum()、EWMA=ppm/1e6）

**检查点**：US7 observability 模块可独立测试

---

## 第 10 阶段：打磨与跨领域关注点

**目的**：性能门控、覆盖率、集成、文档

- [X] T036 [P] 在 `circuit-breaker-benchmarks/src/jmh/java/dev/circuitbreaker/benchmarks/AcquireReleaseBenchmark.java` 实现 acquire/release JMH 基准（含 `-gc` 分配计数）
- [X] T037 配置性能门控：tryAcquire P50<100ns、release P50<50ns、零分配（CI 回归红线）
- [X] T038 跨线程 release 集成测试（并发段求和归零）+ 热更新在途 release 集成测试
- [X] T039 覆盖率达标（行≥80%/分支≥70%/方法≥85%），补齐缺口
- [X] T040 热路径静态门控：禁 Math.exp / 禁对象 new / 禁 synchronized（评审或 ArchUnit 规则）
- [X] T041 [P] 更新 `CLAUDE.md`/README 与模块文档（英文标识符/Javadoc，中文设计文档）
- [X] T042 运行 quickstart/示例校验端到端可用性

---

## 依赖与执行顺序

### 阶段依赖
- **搭建（第1阶段）**：无依赖，立即开始
- **基础（第2阶段）**：依赖搭建；**阻塞所有用户故事**
- **用户故事（第3-9阶段）**：均依赖基础完成；US1 优先（MVP），其后 US2（同 P1），再 US3/US4（P2），再 US5/US6/US7（P3）
- **打磨（第10阶段）**：依赖全部目标用户故事完成

### 用户故事依赖
- **US1（P1）**：基础完成后即可开始，不依赖其他故事（MVP）
- **US2（P1）**：基础完成后即可开始，与 US1 并行（不同文件）
- **US3/US4（P2）**：基础完成后即可开始
- **US5（P3）**：依赖基础（version 校验在 release，复用 US1 release 路径）
- **US6（P3）**：依赖 core 模块（US1+）的 tryAcquire/release
- **US7（P3）**：依赖 ResourceState 的 LongAdder/ewmaState（只读）

### 每个故事内部
- 先测试（须先失败）→ 再实现 → 接入引擎
- 每个故事完成后再进入下一优先级

### 并行机会
- 第1/2阶段内 [P] 任务可并行
- 基础完成后 US1 与 US2 可并行（ratelimit/ 与 breaker/ 不同包）
- 各故事的 [P] 测试任务可并行

---

## 实施策略

### 先交付 MVP（仅 US1）
1. 完成搭建 + 基础
2. 完成 US1（限流器）→ 独立测试 → 即为可用 MVP
3. 验证 SC-001/002（JMH 纳秒 + 零分配）

### 增量式交付
US1 → US2 → US3/US4 → US5/US6/US7，每个故事独立测试、增量价值，不破坏先前故事。

---

## 备注
- [P] = 不同文件、无依赖
- [Story] 标签关联用户故事，便于追溯
- 每个故事可独立完成与测试；实现前先确保测试失败
- 每个任务或逻辑组完成后提交
- flow-3 就地回写 `[X]`，不重跑生命周期
