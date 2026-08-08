# AA 复验审查（R3）：极端 TPS 场景全矩阵复核 + 补漏判定

> 角色：AA-REVIEW（架构设计 / 代码审查）
> 日期：2026-08-08（R3 复验轮，继 R2 之后以当前 HEAD 全矩阵复核）
> 对象仓库：`/Users/jon/opensource/circuit-breaker`（git HEAD `4770396`；`src/` 工作树与 HEAD 一致，唯一未提交改动为 `docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md`——SA v3 返工版；`docs/qa/AA_EXTREME_TPS_REVIEW_R2.md` 未跟踪）
> 上游基线：
> - `docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md`（SA 需求说明，**v3**，已过 QA 验收：§0b 基线、§0c SA 复验、§2 场景矩阵、§3 五门禁 14 条 AC）
> - `docs/qa/AA_EXTREME_TPS_REVIEW_R2.md`（AA R2 复验产出：4 处 P1/P2 修复全部落地、唯一发现 F1=SA TA-4 行陈旧标记、无真实必补测试缺口）
> 审查方式：逐文件复读全部热路径源码 + 关键测试证据核对 + 全量 `./gradlew test --rerun-tasks`（**43 suites / 263 tests / 0 failures / 0 errors / 0 skipped**，本机复跑）作为无回归基线；第一性原理四目标逐条核查；§2 场景矩阵 A–E 五类逐条复核（含 4 处 P1/P2 修复回归确认、P3 残余点、修复后的行为边界）。每条证据 `文件:行`。
> 审查范围：仅复核 + 补漏；不重复实施已落地改动。**除发现新缺陷外不要求 DA 改动**（lessons §13 防盲目改动回归）。

---

## 0. 结论摘要

- **4 处 P1/P2 修复在 HEAD `4770396` 全部落地且无回归**（对照 263/0/0 全绿基线复跑确认），结论与 R2 一致，无回退、无重改需求。
- **无新增 P0/P1/P2 代码缺陷**。全部热路径不变量保持（§3）：热路径无 `Math.exp`、无 `synchronized`、零堆分配、单 `AtomicLong` 不自 stripe、`@Contended` 布局由测试强制。
- **本轮新发现 2 项（均非代码缺陷，不阻塞）**：
  - **F1（LOW，doc-only）**：SA v3 文档头部声称「44 suites 聚合 263 条」，本机 `--rerun-tasks` 复跑实测 **43 suites / 263 tests**（QA 已记为 LOW 观察项，R3 复核坐实）。263 条测试数一致，仅 suite 数 off-by-one。
  - **F2（MED，行为边界 / 测试缺口 / 文档引用不准）**：**LT-5 稀疏流量 re-seed 边界**——`EwmaCircuitBreaker.java:178` 的 re-seed 判定在 gap ≥ max(8τ, `EW_IDLE_RE_SEED_FLOOR_MS=100`) 时把 count 重置为 1，导致**间隔 ≥ 该阈值的稀疏但 100% 失败流量 count 永远凑不齐 minCalls、熔断器永不跳闸**（τ=1ms 时阈值即 100ms；默认 τ=5000ms 时阈值 40s）。这是 R1 100ms 绝对下限修复引入的设计权衡（源码 `:172-177` 注释已声明「long idle」语义），**非回归缺陷**；但 SA 文档 §2 LT-5 行期望行为「持续失败（即使稀疏）最终跳闸」在该边界不严格成立，且 SA 引用的「`ResourceIsolationBreakerTest`（τ=1ms 回归）」**引用不准确**（该测试 `:17` 用 `ewmaTauMs=1000`，非 1ms），**全仓无 τ≤10ms + 稀疏间隔的专项边界测试**。
- **P3 残余点（E1/E2/E3）复核维持记录**（§5），无新风险。
- **对前序 AA 文档基线决策**：不改写 `AA_EXTREME_TPS_REVIEW.md` / `R2`（历史审计记录，避免破坏追溯）；本轮结论独立落于本文档。
- **下游指令**：DA **无必做代码改动**（可选延后 HT-4 同值写跳过微优化）；TA **可选**补 LT-5 边界测试（确定性注入时钟）；SA **建议**修正 suite 数（44→43）与 LT-5 行引用。

---

## 1. 审查对象与证据来源

- 全部热路径源码（逐行复读）：
  - `core/FlatExecutionEngine.java`（tryAcquire/release 编排、mask 分派）
  - `core/ratelimit/LazyTokenBucket.java`（惰性令牌桶）
  - `core/breaker/EwmaCircuitBreaker.java` / `EwmaAlpha.java`（三态 + EWMA + 代际）
  - `core/concurrency/SegmentedConcurrency.java`（分段并发）
  - `core/system/SystemOverload.java`（CPU 探针 + 概率短路）
  - `core/TokenCodec.java` / `core/ClockSource.java`（token / 单调时钟）
  - `core/ResourceManager.java` / `ResourceState.java` / `ResourceConfig.java` / `reload/ConfigSwapper.java`（配置/状态分离、RCU）
  - `core/PolicyBuilder.java` / `core/PolicySpec.java`（策略校验）
  - `reactive/CircuitBreakerOperator.java` / `observability/CircuitBreakerCollector.java`（包装 / 观测）
  - `core/BlockCode.java` / `Outcome.java` / `GovernanceException.java`
- 测试证据：`TpsDynamicsBreakerTest` / `TpsDynamicsTokenBucketTest` / `TpsDynamicsConcurrencyTest` / `SystemOverloadTest` / `EwmaCircuitBreakerTest` / `TokenCodecDefectTest` / `LowTpsBreakerTest` / `ResourceIsolationBreakerTest` / `HotPathGuardTest` / `ContendedPaddingGuardTest` / `FaultInjectionTest` 等。
- 基线事实：`./gradlew test --rerun-tasks` → **43 suites / 263 tests / 0 failures / 0 errors / 0 skipped**（`build/test-results/test/` 聚合确认）。
- 设计事实源：`docs/brd/design.md`、`docs/system/07_ALGORITHM_DEEP_DIVE.md`（同 SA/R2 引用）。

---

## 2. 第一性原理核查（SA §1.3 / §3.2 门禁）

| 目标 | 核查结论 | 证据 |
|---|---|---|
| **纳秒级** | tryAcquire 单次 `ClockSource.nowRelMs()`（`nanoTime/1M` 一次 long 除法，`ClockSource.java:21-24`）；`SystemOverload.maybeShed()` 单 volatile 读（`SystemOverload.java:36`）；breaker/令牌桶各一次 CAS 自旋；并发段 17 次 volatile 读 + 1 写（高 limit 跳过预检）；TokenCodec 位移+掩码 | ✅ 成立 |
| **零堆分配** | 热路径无 `new`（错误路径的 `IllegalArgumentException`/`GovernanceException` 为 off-happy-path 异常物化，`FlatExecutionEngine.java:23/27/81` 等）；`ThreadLocalRandom.current()` 为唯一潜在惰性分配点——`maybeShed` 已被 `shed>0 &&` 短路（`SystemOverload.java:37`，SHED_PERMILLE=0 时不触 TLR，HT-5 确认成立）；`SegmentedConcurrency.tryAcquire` 的 TLR 属 BR-031 设计（per-thread 一次性成本） | ✅ 成立 |
| **无锁化** | `synchronized` 仅 `ResourceManager.register`（控制面 setup，`ResourceManager.java:24`）；`HotPathGuardTest` 2/0/0 绿；无锁 CAS 自旋有界（令牌桶 `LazyTokenBucket.java:33-50`、EWMA `EwmaCircuitBreaker.java:152-191`、breakerState `transition` `:125-137`） | ✅ 成立 |
| **惰性时间推导** | 令牌桶 `refillTokens` 与 EWMA α 全部由请求时刻 `nowMs` 推导；治理侧无后台定时线程（唯一后台 = CPU 探针 1s 采样，`SystemOverload.java:169-191`，off 热路径 BR-042）；探针生命周期完全 off request path | ✅ 成立 |
| **配置/状态分离** | `STATES`（AtomicReferenceArray，set-once，`ResourceManager.java:18/33`）不随 `CONFIGS` 重建；release 靠 token 内嵌 mask/version/bucketIdx/resourceId 回滚（`FlatExecutionEngine.java:74-96`，BR-053）；`ConfigSwapper.swap` 版本单调 + CAS 循环（`ConfigSwapper.java:38-52`） | ✅ 成立 |
| **自描述 token** | 位布局 [sign:1][time:27][version:10][bucket:4][rid:10][mask:12] 未变（`TokenCodec.java:4-6`）；release 解码 mask/version/bucket/rid，跨资源 token 拒绝（`FlatExecutionEngine.java:79-85`） | ✅ 成立 |
| **RCU 热换** | `publishConfig`/`compareAndExchangeConfig` 原子发布（`ResourceManager.java:75-86`）；版本单调严格递增（`ConfigSwapper.java:40-49`）；`version & VERSION_MASK` 进入 token，release 侧版本匹配判定（`FlatExecutionEngine.java:94`） | ✅ 成立 |

**结论：第一性原理四目标 + 配置/状态分离/自描述 token/RCU 全部保持，无破坏。**

---

## 3. 设计不变量复核（SA §3.2 AC-4..AC-7）

| 不变量 | 复核结果 | 证据 |
|---|---|---|
| AC-4 HotPathGuardTest 绿 | ✅ 2/0/0 | `HotPathGuardTest.java:26-39`（无每请求 `Math.exp`、无热路径 `synchronized`；`EwmaAlpha.<clinit>` 与 `ResourceManager.register` 为显式豁免） |
| AC-5 热路径零堆分配 | ✅ | grep 实证：热路径文件仅错误路径分配；TLR 惰性分配不落于 `maybeShed` 首调（短路成立）；`ContendedPaddingGuardTest` 1/0/0 绿 |
| AC-6 单 AtomicLong + 自旋、不 stripe、TLR probe 路由 | ✅ | 令牌桶 `bucketState`、EWMA `ewmaState`、breaker `breakerState` 均单 AtomicLong；并发段 TLR probe（`SegmentedConcurrency.java:44`） |
| AC-7 配置/状态分离、token 位布局不变 | ✅ | §2 末行已述；`TokenCodec.java` 位布局与 R2 一致未动 |

---

## 4. 对抗性场景矩阵 A–E 逐条复核（SA §2）

> 每条给出「复核结论 + 证据」。✅=成立/已覆盖；⚠=边界或文档问题（见 §6 F2）。

### A. 低 TPS

| ID | 复核结论 | 证据 |
|---|---|---|
| **LT-1** | ✅ 空闲累积饱和到 capacity，首请求放行且 tLast 推进 | `LazyTokenBucket.java:38-49`（`nTok=min(capacity, tok+add, TOKEN_MASK)` 封顶）；`LowTpsTokenBucketTest.longIdleFillsToCapacity` / `sparseCallsAccumulate` 通过 |
| **LT-2** | ✅ 空闲后首个成功 α=1 全衰减清除陈旧 ppm；wrap-aliased 周期空闲由 R1 绝对锚点兜底 | `EwmaCircuitBreaker.java:149-151,178`；`TpsDynamicsBreakerTest.silenceThenSuccessFullyResetsPpm` + `EwmaCircuitBreakerTest.wrapAliasedPeriodicIdleDoesNotFreezeStalePpm`（`:173-190`）通过 |
| **LT-3** | ✅ 抹零对策成立（未生成完整 token 不推进 tLast） | `LazyTokenBucket.java:42-45`（`nTok<1 → return false`，tLast 不动）；`LowTpsTokenBucketTest.subSecondNoTokenAndTlastNotAdvanced` / `exactlyOneSecondOneToken` 通过 |
| **LT-4** | ✅ 默认 `minCalls=1` 假跳闸面已加固 | `PolicyBuilder.java:24` `private int minCalls = 10;` + `:20-23` 注释；`PolicyBuilderTest.defaultMinCallsMeetsS5ColdStartFloor` 通过；冷启动首失败 count=1<10 不跳闸（`EwmaCircuitBreaker.java:115-117`） |
| **LT-5** | ⚠ **边界未覆盖（F2）** | 见 §6 F2 |

### B. 高 TPS

| ID | 复核结论 | 证据 |
|---|---|---|
| **HT-1** | ✅ 单 AtomicLong CAS 自旋有界、无活锁；放行率 ≤ qps | `LazyTokenBucket.java:33-50` CAS 循环；`ConcurrencyStressTest` 通过 |
| **HT-2** | ✅ 时钟开销 = 单次 `nanoTime/1M` 除法；tryAcquire/release 各一次 `nowRelMs()` | `ClockSource.java:21-24`；性能属性，无正确性角度，不补专项（R2 一致） |
| **HT-3** | ✅ 条件化预检已实施；高 limit 退化为后检 + 偶发 rollback；过冲 ≤ SEG | `SegmentedConcurrency.java:62`（`limitPerSeg<=2` 才预检）+ `:70-78` 后检兜底；`TpsDynamicsConcurrencyTest.highLimitSkipsPrecheckButStillEnforcesGlobalLimitWithinBoundedOvershoot`（`:75-119`）通过 |
| **HT-4** | ✅ R1 锚点每次 committed update 提交（正确性代价保留），无回归 | `EwmaCircuitBreaker.java:188`（仅 CAS 成功后 `lastEwmaUpdateMs.set`）；同 ms 同值写跳过微优化未实施（观察项 F3） |
| **HT-5** | ✅ TLR 惰性分配不落于 `maybeShed` 首调 | `SystemOverload.java:37` `shed>0 && ThreadLocalRandom...` 短路成立；`ConcurrencyStressTest` 通过 |
| **HT-6** | ✅ LongAdder 固有设计，无热 Cell 争用 | `ResourceState.java:28-29`；高阻断率性能属性，不补专项（R2 一致） |

### C. TPS 突增突降

| ID | 复核结论 | 证据 |
|---|---|---|
| **TS-1** | ✅ 突发 ≤ capacity 封顶、随后立即阻断（不借未来令牌） | `LazyTokenBucket.java:41`（nTok 封顶）+ `:42-44`（nTok<1 阻断）；`TpsDynamicsTokenBucketTest.spikeDrainsBucketThenBlocksImmediateFollowup` 通过 |
| **TS-2** | ✅ ms 粒度平滑恢复；qps 非 1000 倍数 ms-floor 欠放为已知 S6 权衡 | `LazyTokenBucket.java:68-84`；`TpsDynamicsTokenBucketTest.spikeThenPartialRefillResumesAtRate` + `subSecondRefillIsMsGranular`（qps=1500）通过 |
| **TS-3** | ✅ 压缩攻击两侧均已固化：微突发低通阻尼 + 持续同 ms 失败最终跳闸（量化 ~0.693τ） | `EwmaCircuitBreaker.java:181` 低通；`TpsDynamicsBreakerTest.sustainedCompressedFailureBurstAcrossTauHorizonUltimatelyTrips`（`:181-209`，断言 450..1200ms 跳闸）+ `microBurstFailuresDampenedByLowAlpha` 通过 |
| **TS-4** | ✅ OPEN↔HALF_OPEN 振荡受 openMillis 约束、探针丢失自愈、probeGen 防 hijack | `EwmaCircuitBreaker.java:58-83`（probe deadline 自愈 + re-arm 置 probeGen=-1）；`CircuitBreakerStateMachineTransitionTest` / `HalfOpenStaleReleaseBugTest` / `EwmaCircuitBreakerTest.lostProbeSelfHealsAfterGrace` 通过 |
| **TS-5** | ✅ ppm 爬升/下降速率匹配 τ；τ 过小/过大的权衡由 S4 检查 | `EwmaCircuitBreaker.java:181-183`；`TpsDynamicsBreakerTest.sustainedErrorsOverTauHorizonTripBreaker` / `jitteredIntervalsStillTripWhenSustained` 通过 |
| **TS-6** | ✅ 突增期间热换：在途 release 按 token 内嵌 mask/version 回滚；版本单调 | `FlatExecutionEngine.java:74-96`；`ConfigSwapper.java:38-52`；`TpsDynamicsEngineTest` / `ConfigSwapperConcurrencyTest` 通过 |

### D. 时间异常

| ID | 复核结论 | 证据 |
|---|---|---|
| **TA-1** | ✅ 时钟回拨 dt clamp 到 0、阻断而非污染 | `LazyTokenBucket.java:37`（`Math.max(0, nowMs-tLast)`）+ `:42-44`；`TpsDynamicsTokenBucketTest.backwardClockBlocksRatherThanCorrupts` 通过 |
| **TA-2** | ✅ 负 dt 经 modular 减变巨大 → α=1 全衰减重播种，无污染 | `EwmaCircuitBreaker.java:159`（`& EW_LAST_MASK` modular）；`TpsDynamicsBreakerTest.clockReversalWithin20BitDoesNotCorruptEwma` 通过 |
| **TA-3** | ✅ 大步前跳：令牌桶封顶无风暴、EWMA 重播种不假跳闸 | `LazyTokenBucket.java:74-79`（饱和守卫）；`TpsDynamicsTokenBucketTest.extremeForwardJumpCapsTokensAtCapacityWithoutStorm`（`:145-171`）+ `TpsDynamicsBreakerTest.hugeForwardJumpReSeedsEwmaWithoutFalseTrip`（`:217-239`）通过 |
| **TA-4** | ✅ uptime 级环绕已覆盖（RT 模减精确 + 周期别名空闲防御） | `TokenCodec.java:82-84`（`rtMs` 模减）；`TokenCodecDefectTest.rtMsCorrectWhenTimeFieldWrapsAcrossUptime`（`:93-101`，2^27 wrap 后 RT 精确=153）+ `EwmaCircuitBreakerTest.wrapAliasedPeriodicIdleDoesNotFreezeStalePpm`（`:173-190`，2^24 别名空闲）通过 |
| **TA-5** | ✅ 探针只依赖 CPU 采样不依赖墙钟；无隐藏时间依赖 | `SystemOverload.java:41-55`（`onCpuSample(double)` 无时间参数）+ `:173-177`（`getCpuLoad()` range 校验）；`TpsDynamicsSystemOverloadTest` 通过 |

### E. 生命周期

| ID | 复核结论 | 证据 |
|---|---|---|
| **LC-1** | ✅ STATES 先于 CONFIGS 发布（AtomicReferenceArray volatile 序） | `ResourceManager.java:18,33,37`（STATES.set → seed → CONFIGS.set）；`ResourceManagerBoundsTest` / `ErrorHandlingAndResourceReleaseTest` 通过 |
| **LC-2** | ✅ 高压并发热换 CAS 循环版本单调、单版生效 | `ConfigSwapper.java:38-52`；`ConfigSwapperConcurrencyTest` 通过 |
| **LC-3** | ✅ 双探针竞态封口：身份校验 finally + join 超时 interrupt | `SystemOverload.java:101-103,118-127,156-162`；`SystemOverloadTest.rapidStopStartCyclesDoNotLeakProbeThreads`（`:97-112`）+ `interruptedProbeExitsViaInterruptPathAndRestartsCleanly`（`:115-144`）通过 |
| **LC-4** | ✅ 热换关闭 CB 后陈旧 token release 不喂 EWMA、不假跳闸 | `EwmaCircuitBreaker.java:92-94`（`cfg.mask & MASK_CIRCUIT_BREAKER == 0 → return`）；`StaleTokenAfterCbHotSwapOffTest` 通过 |
| **LC-5** | ✅ 探针 catch Throwable 不死、VirtualMachineError 传播；测试注入需 seam，价值低（R2 一致） | `SystemOverload.java:181-189`；无 seam 不补 |

---

## 5. P3 残余点复核（E1/E2/E3）

| # | 级别 | 前序状态 | HEAD `4770396` 复核 | 处置 |
|---|---|---|---|---|
| E1 | P3 | `decodeTime`/`rtMs` 死代码（`TokenCodec.java:60-84`），生产无调用 | grep 实证：`src/main` 仅定义 + javadoc；`TokenCodecDefectTest`/`TokenCodecTest` 使用 | **维持记录**（删属 API 破坏，不改） |
| E2 | P3 | 直构 `new ResourceConfig(...)` 绕过容量校验 | `ResourceConfig.java:23-35` 构造器无校验；builder 侧守卫已加（`PolicyBuilder.java:63-72`）；`PolicyBuilderValidationTest` 5 条容量用例全绿 | **维持记录**（builder 是推荐入口） |
| E3 | P3 | version 10 位回绕（1024 次热换）陈旧 token 可能匹配新 cfg.version | `TokenCodec.java:9/27-32` javadoc 明确「BR-052 ABA window」；跳闸仍需 count≥minCalls ∧ ppm≥threshold，单陈旧样本不致误跳闸 | **维持记录** |

---

## 6. 本轮发现

### F1（LOW，doc-only）：SA v3 声称 44 suites，实测 43

- **证据**：`docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md:5` / `:35` 声明「44 suites 聚合 263 条」；本机 `./gradlew test --rerun-tasks` 后 `build/test-results/test/` 聚合 **43 个 suite XML / 263 tests / 0 failures / 0 errors / 0 skipped**（逐一列名确认，与 `src/test` 43 个测试类一一对应）。
- **判定**：263 条测试数一致，仅 suite 数 off-by-one（QA 已在上一轮记为 LOW 观察项，R3 坐实）。**纯文档一致性问题，非代码缺陷，不阻塞。** 建议 SA 将两处 44 → 43。
- 顺带确认：`AA_EXTREME_TPS_REVIEW_R2.md:40` 正确写「43 个 suite 聚合」，SA 文档与该值不符。

### F2（MED，行为边界 / 测试缺口 / 文档引用不准）：LT-5 稀疏流量 re-seed 边界

- **代码证据**：
  - `EwmaCircuitBreaker.java:178`：`if (absIdle || (dtMs > 0 && (dtMs >> 3) >= cfg.ewmaTauMs && dtMs >= EW_IDLE_RE_SEED_FLOOR_MS)) { next = packEwma(gNow, nowQ, 1, xPpm); }` —— **re-seed 时 count 重置为 1**。
  - `EwmaCircuitBreaker.java:35`：`EW_IDLE_RE_SEED_FLOOR_MS = 100`。
  - `EwmaCircuitBreaker.java:115-117`：跳闸条件 `count ≥ minCalls ∧ ppm ≥ errThresholdPpm`。
- **行为推演**：任何 **gap ≥ max(8τ, 100ms)** 的样本都会触发 re-seed（count=1）。因此对「间隔 ≥ 该阈值」的稀疏流量——即使 **100% 失败、持续无限久**——count 永远 < minCalls，熔断器**永不跳闸**：
  - τ=1ms：阈值 = 100ms（8τ=8ms < 100ms floor，floor 主导）。间隔 100ms 的 100% 失败永不跳闸。
  - 默认 τ=5000ms：阈值 = 40s（8τ 主导）。间隔 40s+ 的 100% 失败永不跳闸。
- **定性**：这是 R1「100ms 绝对下限」修复的**设计权衡**——源码 `:172-177` 注释明确声明「long idle only makes sense above a physically meaningful gap」，且 re-seed 与「α=1 全衰减」语义一致。改小阈值会重新引入 `ResourceIsolation` 类回归（tiny τ 时任何 8ms 间隔重置 count，minCalls 永远凑不齐）。**因此非代码缺陷，不建议改代码。**
- **但存在 3 个真实缺口**：
  1. **SA 期望行为不精确**：SA §2 LT-5 期望「持续失败（即使稀疏）最终跳闸」在该边界不严格成立，未量化「稀疏」的精确阈值（gap ≥ max(8τ,100ms)）。
  2. **SA 测试引用不准**：SA §2 LT-5 行引用「`ResourceIsolationBreakerTest`（τ=1ms 回归）」——实测该测试 `ResourceIsolationBreakerTest.java:17` 用 `ewmaTauMs=1000`（非 1ms），且测的是**资源隔离**而非**稀疏流量边界**。
  3. **无边界专项测试**：全仓 grep 无 τ≤10ms + 稀疏间隔（gap ≥ 100ms）的确定性用例。`LowTpsBreakerTest.sparseTauSpacedFailuresStillTrip` 用 τ=1000、间隔 1000ms（gap=τ < 8τ），未触边界。
- **处置建议**：
  - SA：在 §2 LT-5 行补正「已有覆盖」与期望行为量化（re-seed 边界 = max(8τ, 100ms)；gap ≥ 阈值时 count 重置，稀疏 100% 失败不会跳闸——这是 S3 检查的已知权衡，配 SLA 事实时 S3 会 ERROR 提示）。
  - TA（可选）：补确定性边界测试——τ=1ms、minCalls=5、间隔 100ms 的持续失败断言「CLOSED（count 不累积）」与间隔 99ms 断言「count 累积」的边界对照（旧代码不适用，纯新用例；无需旧失败新通过，属行为锁存）。
  - DA：**不要求代码改动**。

### F3（LOW，观察）：HT-4 `lastEwmaUpdateMs` 同值写跳过微优化未实施 + 非 @Contended

- **证据**：`EwmaCircuitBreaker.java:188` 仍无条件 `set(nowMs)`（未加 `nowMs != absLast` 守卫）；`ResourceState.java:49` `lastEwmaUpdateMs` 未 `@Contended`（与 `probeGen` 共享尾部 cache line）。
- **定性**：R2 已记「正确性无影响、建议延后」；R3 复核维持。同 ms 多线程重复写同一值是 cache-line 写争用的微优化项，不影响纳秒级数量级；`probeGen` 仅 HALF_OPEN 低频读写，争用有限。若后续实施须复跑 `./gradlew jmh` + `ContendedPaddingGuardTest`（AC-12）。

### F4（LOW，观察）：SegmentedConcurrency 热路径 TLR 惰性分配

- **证据**：`SegmentedConcurrency.java:44` `ThreadLocalRandom.current()` 在 MASK_CONCURRENCY 热路径每个 acquire 调用。
- **定性**：`ThreadLocalRandom.current()` 首调 per-thread 惰性初始化（一次性常数成本），非每请求分配；BR-031 设计（TLR probe 路由），与 HT-5「maybeShed 短路」互补。维持记录，不改。

---

## 7. 交接指令（下游）

### DA（编码实现）
- **无必做改动**（本轮 0 新 P0/P1/P2 代码缺陷；F2/F3 均不要求改代码）。
- **可选延后**：HT-4「同 ms 同值写跳过」微优化（`EwmaCircuitBreaker.java:188` 加 `nowMs != absLast` 守卫）——正确性无关，若实施须复跑 `./gradlew jmh` + `ContendedPaddingGuardTest`（AC-12）。建议本轮不做。
- 不得无故改动 §4「已核实成立」列表中的任何行为（lessons §13）。

### TA（测试执行）
- **无必做新增**（TS-3/TA-3/TA-4/LC-3/HT-3 均已专项覆盖并通过，R2/R3 复核一致）。
- **可选**：F2 的 LT-5 边界对照用例（τ=1ms，gap 100ms vs 99ms 的 re-seed 行为锁存）——确定性注入时钟，价值中等；无则跳过。
- 本轮 `./gradlew test --rerun-tasks` 已全绿（43 suites / 263/0/0），可作为 TA→QA 交接证据。

### SA（需求文档）
- **建议修正 F1**：文档头部与 §0c 两处「44 suites」→「43 suites」（263 条数不变）。
- **建议补正 F2**：§2 LT-5 行补正测试引用（`ResourceIsolationBreakerTest` 非 τ=1ms）并量化 re-seed 边界；期望行为注明「gap ≥ max(8τ,100ms) 的稀疏流量 count 不累积」为 S3 已知权衡。

---

## 8. 结论

第一性原理 + 对抗性全矩阵复核完成：**4 处 P1/P2 修复全部落地无回归；无新增 P0/P1/P2 代码缺陷；设计不变量全保持；263/0/0 全绿基线确认。** 新发现 2 项（F1 doc-only、F2 行为边界/测试缺口/文档引用不准）均非代码缺陷、不阻塞验收。DA 无必做改动，TA 无必做新增，SA 建议修正 2 处文档表述。QA 可在 DA 无改动、TA 无新增的前提下，以当前 263 全绿基线直接做最终验收。

---

*R3 复验完成。复核结论独立落于本文档（HEAD `4770396` 为审查对象）；前序 R2 结论维持。*
