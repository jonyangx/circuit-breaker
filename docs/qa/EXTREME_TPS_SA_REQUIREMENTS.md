# SA 需求分析：极端 TPS 场景审查范围、场景清单与验收标准

> 角色：SA-REVIEW（需求分析）
> 日期：2026-08-08
> 对象仓库：`/Users/jon/opensource/circuit-breaker`（git HEAD `691377e`，工作树干净，`./gradlew compileJava` 基线通过）
> 上游事实源：`docs/brd/design.md`（§3/§4/§6/§8/§10）· `docs/system/07_ALGORITHM_DEEP_DIVE.md`（§5/§6/§8/§9）
> 本文档是本次"第一性原理 + 对抗性 review → 优化实施 → 极端 TPS 测试 → 验收"工作流的**需求基线**，供 AA 审查、DA 实施、TA 测试、QA 验收共同引用。

---

## 0. 审查目标（一句话）

**在不违反 CLAUDE.md 四条设计不变量（纳秒级 / 零堆分配 / 无锁化 / 治理侧无后台定时器）的前提下，验证并加固组件对极端 TPS 场景（低 TPS、高 TPS、TPS 突增突降、时间异常、生命周期竞态）的平滑支撑能力。**

验收判据贯穿整条链：**AA 每条发现必须有 `文件:行` 证据；DA 的每个修复必须有回归测试在旧代码下失败、新代码下通过（docs/lessons.md §3 原则）；TA 的测试必须覆盖下文的场景矩阵；QA 只审查证据、不重跑测试。**

---

## 1. 审查范围（Scope）

### 1.1 在范围内（IN-SCOPE）

热路径治理组件（数据面）+ 生命周期机制（控制面）：

| 组件 | 文件 | 角色 |
|---|---|---|
| 扁平执行引擎 | `core/FlatExecutionEngine.java` | tryAcquire/release 编排、mask 分派 |
| 惰性令牌桶 | `core/ratelimit/LazyTokenBucket.java` | QPS 限流、惰性时间推导 |
| EWMA 熔断器 | `core/breaker/EwmaCircuitBreaker.java` | 三态状态机、代际防 ABA |
| α 分段近似 | `core/breaker/EwmaAlpha.java` | 无 `Math.exp` 的衰减近似 |
| 分段并发控制 | `core/concurrency/SegmentedConcurrency.java` | 近似并发限流 |
| 系统过载分级丢弃 | `core/system/SystemOverload.java` | CPU 探针 + 概率短路 |
| Token 编解码 | `core/TokenCodec.java` | 自描述 64 位 token |
| 单调时钟 | `core/ClockSource.java` | nanoTime 相对毫秒 |
| 配置/状态分离 | `core/ResourceManager.java` / `ResourceState.java` / `ResourceConfig.java` / `reload/ConfigSwapper.java` | RCU 热换、稳定状态槽 |
| 策略校验 | `core/PolicyBuilder.java` / `PolicySpec.java` | 参数/SLA 不变量 |
| 响应式包装 | `reactive/CircuitBreakerOperator.java` | doFinally 三态释放 |
| 观测 | `observability/CircuitBreakerCollector.java` | scrape 侧只读 |

### 1.2 范围外（OUT-OF-SCOPE，审查时不做功能改动）

- 集群/分布式限流（design §11.2 明确不适配，打破无锁设计）。
- 按 `origin` 等细粒度限流、精准秒级集群 QPS 同步。
- JMH 基准工程（`src/jmh`）本身不作为改动对象；热路径若被修改，需复跑基准确认不劣化。

### 1.3 第一性原理核查维度（每条热路径步骤）

对 `tryAcquire`/`release` 逐步骤核对以下目标是否每一步都支撑：

1. **纳秒级**：`ClockSource.nowRelMs()`（nanoTime + 一次 long 除法）、`SystemOverload.maybeShed()`（volatile 读）、`CONFIGS/STATES` 寻址、`EwmaCircuitBreaker.tryAcquire`（breakerState 读）、`LazyTokenBucket.tryAcquire`（bucketState CAS 自旋）、`SegmentedConcurrency.tryAcquire`（TLR probe + 段读写）、`TokenCodec.encode/decode`（位移+掩码）。
2. **零堆分配**：tryAcquire/release 全程不得 new 对象；`ThreadLocalRandom.current()` 首调是**唯一的潜在惰性分配点**（HT-5）；`GovernanceException` 仅在调用方主动 materialize 时分配（off hot path，design §4）。
3. **无锁化**：除 `ResourceManager.register`（控制面 setup）外不得有 `synchronized`；`HotPathGuardTest` 用 ArchUnit 强制，必须保持绿。
4. **惰性时间推导**：令牌桶 `refillTokens`、EWMA `α` 全部由请求时刻推导；治理侧无定时线程（仅 CPU 探针低频采样）。
5. **配置/状态分离、自描述 token、RCU 热换成立性**：`STATES` 不随 `CONFIGS` 重建；release 靠 token 内嵌 mask/version/bucketIdx/resourceId 回滚；`ConfigSwapper` 版本单调。

### 1.4 对抗性维度（极端场景，见 §2）

站在攻击者/苛刻使用者角度构造失效点，重点覆盖 §2 的 A–E 五大类场景。

---

## 2. 极端 TPS 场景清单（Adversarial Scenario Matrix）

> 每项给出：场景 ID、触发条件、关注模块、期望行为（= 验收基准）、潜在失效点（AA 核查重点）、已有测试覆盖（供 TA 避免重复、补缺）。

### A. 低 TPS（长时间空闲后 / 极稀疏流量）

| ID | 触发条件 | 模块 | 期望行为 | 潜在失效点（AA 核查） | 已有覆盖 |
|---|---|---|---|---|---|
| **LT-1** | 空闲数秒~数小时后首个请求 | 令牌桶 | 空闲期 token 按 `dtMs*qps/1000` 累积并**饱和到 capacity**，首个请求放行且 tLast 推进到 now | 42 位 tLast 在极端 uptime 下溢出；`refillTokens` 的 `dtMs*qps` 饱和守卫（`dtSat`）是否对所有 qps/capacity 组合生效 | `LowTpsTokenBucketTest.longIdleFillsToCapacity` / `sparseCallsAccumulate`；`TpsDynamicsTokenBucketTest.longIdleAfterSpikeRestoresFullBurst` |
| **LT-2** | 空闲后首个请求是失败/成功（EWMA 惰性衰减） | 熔断器 | 空闲后首个**成功**必须清除陈旧高 ppm（α=1 全衰减或 re-seed）；首个**失败**不得因旧累积假跳闸 | R1 绝对锚点 `lastEwmaUpdateMs` 与 modular 守卫阈值一致性；`EW_IDLE_RE_SEED_FLOOR_MS=100` 与 τ 的组合是否漏掉"周期性 k·2²⁴ms 别名空闲" | `TpsDynamicsBreakerTest.silenceThenSuccessFullyResetsPpm`；R1 回归（wrap-aliased periodic idle） |
| **LT-3** | qps<1/s 的亚秒请求 | 令牌桶 | 抹零对策成立：未生成完整 token 时**不推进 tLast**、返回阻断，时间差持续累积 | `dtMs*qps/1000` 在 qps=1、dtMs=999 时=0 的行为；qps 极小（如 0.001/s 由 builder 外传）时的退化 | `LowTpsTokenBucketTest.subSecondNoTokenAndTlastNotAdvanced` / `exactlyOneSecondOneToken` |
| **LT-4** | 低 TPS + 冷启动首个失败 | 熔断器配置 | **明确默认 minCalls=1 的假跳闸风险**并给出处置结论（加固默认值或文档声明）；S5 检查仅在校验 slaFacts 时生效 | `PolicyBuilder` 默认 `minCalls=1`；`enableCircuitBreaker` 未接 `minimumCalls` 时单失败即跳闸（count=1 ≥ minCalls=1 且 ppm=1M ≥ 阈值） | 无（配置默认值层，需 AA 判断） |
| **LT-5** | 小 τ + 稀疏流量 | 熔断器 | `count` 能跨多次请求累积到 minCalls；持续失败（即使稀疏）最终跳闸 | τ=1ms、间隔 100ms~8s 时 re-seed 判定是否误触发使 count 永远凑不齐（相对阈值退化已用 100ms 绝对下限修过一次，需复验边界） | `ResourceIsolationBreakerTest`（τ=1ms 回归） |

### B. 高 TPS（持续高压）

| ID | 触发条件 | 模块 | 期望行为 | 潜在失效点（AA 核查） | 已有覆盖 |
|---|---|---|---|---|---|
| **HT-1** | 持续 >50k/s 令牌桶竞争 | 令牌桶 | 单 AtomicLong CAS 自旋**有界**、无活锁；放行率 ≤ qps（不超发） | 高竞争下自旋次数；`refillTokens` 溢出饱和分支是否被高 qps 触发 | `ConcurrencyStressTest`（需确认是否压令牌桶） |
| **HT-2** | 每次 tryAcquire/release 的时钟开销 | 时钟 | `nanoTime/1M` 一次 long 除法纳秒级 | tryAcquire 与 release 各调一次 `nowRelMs()`；是否可缓存/降频（如 release 复用 token 内嵌 time？——注意 RT 语义不可破坏） | 无专项；JMH 基准覆盖 |
| **HT-3** | 高 TPS + 并发门控 | 并发段 | 过冲 ≤ SEG 且有界；release 永不为负 | **happy path 两次 O(16) `sumConcurrency`（预检 + 后检共 32 次 volatile 读）是最高频热点**；高 limit 下预检收益低代价高——评估仅在 limit 较紧时启用预检或单次遍历 | `TpsDynamicsConcurrencyTest`；`SegmentedConcurrencyGlobalLimitTest` |
| **HT-4** | 同 ms 内海量 release（EWMA） | 熔断器 | dt=0→α=0 不抖；count 饱和 65535 不环绕；ppm 不漂移 | `updateEwma` 在 R1 修复后每次 release 多写一次 `lastEwmaUpdateMs`（AtomicLong CAS）——高压下成为额外热路径写；CAS 竞争 | `TpsDynamicsBreakerTest.zeroDtSamplesDoNotShiftEwma` / `microBurstFailuresDampenedByLowAlpha` |
| **HT-5** | 首个请求触发 TLR 初始化 | 全局 | `maybeShed` 的 `ThreadLocalRandom.current()` **首次调用惰性分配不得落在请求热路径** | 类加载/TLS 初始化时序；若 SHED_PERMILLE=0 则不应触发 TLR（当前代码 `shed>0 &&` 短路，确认成立） | 无（需 AA 用 JMH gc profiler / 代码序核查） |
| **HT-6** | 极高阻断率 | 观测 | blockCount/passCount LongAdder 无热 Cell 争用 | Cell[] 增长上限；阻断路径的 LongAdder 是否成为新热点 | 无专项 |

### C. TPS 突增突降（瞬变/抖动）

| ID | 触发条件 | 模块 | 期望行为 | 潜在失效点（AA 核查） | 已有覆盖 |
|---|---|---|---|---|---|
| **TS-1** | 瞬时突发 | 令牌桶 | 突发 ≤ capacity 封顶；随后立即阻断（**不借未来令牌**） | capacity 封顶后的立即阻断是否在所有 qps 下成立 | `TpsDynamicsTokenBucketTest.spikeDrainsBucketThenBlocksImmediateFollowup` |
| **TS-2** | drain 后恢复 | 令牌桶 | 按 qps **ms 粒度**平滑恢复（非整秒阶跃） | **qps 非 1000 倍数时 ms-floor 截断欠放**（S6，如 qps=1500 实际 ~1000/s）；突降流量可能放大欠放 | `TpsDynamicsTokenBucketTest.spikeThenPartialRefillResumesAtRate`（qps=1000）；`subSecondRefillIsMsGranular`（qps=1500 回归） |
| **TS-3** | 同 ms 数千并发失败（压缩攻击） | 熔断器 | 微突发（≤数十）被低通阻尼不跳闸；**持续压缩失败最终必须跳闸** | 量化"同 ms 失败多少会跳闸"：α=dt/τ 下 ppm 爬升曲线；**攻击者用高并发快速失败能否长时间绕过熔断**——需确认收敛阈值在可接受范围 | `TpsDynamicsBreakerTest.microBurstFailuresDampenedByLowAlpha`（仅覆盖 50 次不跳闸，**未覆盖"几千次最终跳闸"这一侧**） |
| **TS-4** | OPEN↔HALF_OPEN 切换 | 熔断器 | openMillis 周期内稳定振荡，无高频自激；探针丢失自愈 | 探针 RT > openMillis 时的自愈 re-arm 是否产生不必要抖震；`probeGen` 是否被陈旧 release hijack | `CircuitBreakerStateMachineTransitionTest`；`HalfOpenStaleReleaseBugTest`；`StartupImmunityBreakerTest` |
| **TS-5** | 健康↔持续失败切换 | 熔断器 | ppm 爬升/下降速率匹配半衰期 τ | τ 过小导致过早/过激跳闸；τ 过大导致响应过慢 | `TpsDynamicsBreakerTest.sustainedErrorsOverTauHorizonTripBreaker` / `jitteredIntervalsStillTripWhenSustained` |
| **TS-6** | 突增期间配置热换 | 全局 | 在途 release 按 token 内嵌 mask/version 回滚；版本单调 | 并发 swap CAS 竞争；version 10 位回绕（1024 次热换） | `TpsDynamicsEngineTest.tokenVersionEmbeddedCorrecltyUnderJitter`；`ConfigSwapperConcurrencyTest` |

### D. 时间异常（Clock anomalies）

| ID | 触发条件 | 模块 | 期望行为 | 潜在失效点（AA 核查） | 已有覆盖 |
|---|---|---|---|---|---|
| **TA-1** | 时钟回拨 | 令牌桶 | dt clamp 到 0，**阻断而非污染状态** | `Math.max(0, nowMs-tLast)` 分支后的 tLast 处理 | `TpsDynamicsTokenBucketTest.backwardClockBlocksRatherThanCorrupts` |
| **TA-2** | 时钟回拨 | 熔断器 | 负 dt 经 modular 减变巨大 → α=1 全衰减重播种，无污染 | `& 0xFFFFF` 对负 dt 的处理；`nowQ = nowMs>>4` 与 last 的量子对齐 | `TpsDynamicsBreakerTest.clockReversalWithin20BitDoesNotCorruptEwma` |
| **TA-3** | 大步前跳（GC STW/挂起后） | 全局 | 令牌桶饱和、EWMA 重播种；**无 token 风暴**（capacity 封顶） | nowRelMs 前跳后令牌桶 `nTok=min(capacity,…)` 是否正确封顶 | 无专项 |
| **TA-4** | 相对时钟长 uptime 环绕 | TokenCodec | token 内 time 27 位回绕后 **RT 模减法仍正确**（单次 RT < 2^27ms） | `rtMs` 只在 release 侧用于 RT；是否有其它逻辑误用 `decodeTime` 绝对值 | `TokenCodecTest`（RT 模减）；无 uptime 级回绕专项 |
| **TA-5** | 时钟异常对探针 | 系统过载 | SHED_PERMILLE 只依赖 CPU 采样，不依赖墙钟；时钟跳变不影响丢弃档位 | `onCpuSample` 无时间参数（天然免疫）；确认无隐藏时间依赖 | `TpsDynamicsSystemOverloadTest` |

### E. 生命周期（极端 TPS 下的竞态）

| ID | 触发条件 | 模块 | 期望行为 | 潜在失效点（AA 核查） | 已有覆盖 |
|---|---|---|---|---|---|
| **LC-1** | 启动期并发注册 + 首个 acquire | 资源管理 | STATES 先于 CONFIGS 发布（volatile 序），首请求不报 "unregistered" | `STATES.set` 先于 `CONFIGS.set`（Defect 3 修复）；极端 TPS 下可见性 | `ResourceManagerBoundsTest`；`ErrorHandlingAndResourceReleaseTest` |
| **LC-2** | 高压下并发热换 | 配置重载 | CAS 循环保证版本单调、单版生效 | `ConfigSwapper.swap` CAS 竞争 + 未注册资源拒绝 | `ConfigSwapperConcurrencyTest` |
| **LC-3** | startProbe/stopProbe 快速循环 | 探针 | 无双探针、无泄漏、停止后 SHED_PERMILLE 归位 | `prev.join(2000)` 超时后旧线程存活 → 双探针写 SHED_PERMILLE；`probeRunning` CAS 时序 | 无专项（HotReloadDisableLeakTest 需确认是否覆盖探针） |
| **LC-4** | 热换关闭断路器后陈旧 token release | 熔断器 | 已关闭的断路器不接受健康样本、不假跳闸 | release 开头 `mask & MASK_CIRCUIT_BREAKER == 0 → return`（N4 修复） | `StaleTokenAfterCbHotSwapOffTest` |
| **LC-5** | 压力下探针异常 | 探针 | 探针线程 catch Throwable 不死；VM 级错误传播 | `probeLoop` 的异常边界（VirtualMachineError 传播） | 无专项 |

---

## 3. 验收标准（Acceptance Criteria）

按以下五个门禁验收，任一不满足即返工。

### 3.1 功能正确性（行为）

- **AC-1**：§2 场景矩阵中每条已有覆盖的测试**保持通过**，且无回归。
- **AC-2**：TA 新增测试覆盖 §2 中标记"无专项/缺口"的场景（至少：**TS-3 压缩攻击最终跳闸侧、HT-3 高 TPS 并发段、TA-3 大步前跳、LC-3 探针循环竞态**），且每个新增用例**能区分修复前后**（旧代码下失败、新代码下通过）。
- **AC-3**：低 TPS 首个请求（LT-1/2）、突增突降（TS-1/2/3/4）、时间异常（TA-1/2/3）、生命周期（LC-2/3/4）均有确定性的行为断言，**不依赖真实 wall-clock**（能注入时间就注入，lessons §17）。

### 3.2 设计不变量（第一性原理）

- **AC-4**：`HotPathGuardTest`（无 `Math.exp` 每请求、无 synchronized 热路径）保持绿。
- **AC-5**：tryAcquire/release 热路径**零堆分配**——若改动热路径，DA 需用 JMH `-prof gc` 复核分配为 0；TLR 惰性分配不得落于请求首调（HT-5）。
- **AC-6**：令牌桶、EWMA、breakerState 保持单 `AtomicLong` + 自旋（**不 stripe**）；并发段保持 TLR probe 路由。
- **AC-7**：配置/状态分离不破坏——`STATES` 不得随 `CONFIGS` 重建；token 位布局（sign/time/version/bucket/resourceId/mask）不得改变（改动须全仓 grep normative 常量，lessons §8）。

### 3.3 对抗性健壮性

- **AC-8**：时钟回拨/前跳后无状态污染（TA-1/2/3 断言 token 数、ppm、tLast 不越界）。
- **AC-9**：高压 CAS 自旋有界、无活锁（HT-1/4）；并发过冲 ≤ SEG + 有界（HT-3）。
- **AC-10**：半开探针丢失自愈、probeGen 防 hijack（TS-4）；探针 start/stop 无竞态（LC-3）。
- **AC-11**：TS-3 压缩攻击——**明确给出"同 ms 失败多少会跳闸"的量化结论**，确认阈值在可接受范围（或证明并发门控/其他机制兜底）。

### 3.4 性能

- **AC-12**：DA 实施优化后 `./gradlew build` 通过；若热路径被改动，复跑 `./gradlew jmh` 对比基线，**不允许纳秒级单次开销显著劣化**（除修复正确性必需的最小代价）。

### 3.5 交付物

- **AC-13**：AA 输出缺陷清单（含严重程度 P0/P1/P2 + `文件:行` 证据）；DA 输出改动与注释；TA 输出测试报告与覆盖矩阵；QA 输出最终验收结论。报告落 `docs/` 或独立 review 文档。
- **AC-14**：`./gradlew test` 全部通过，且全量测试结果作为 TA→QA 交接证据。

---

## 4. 交接给 AA 的核查重点（AA 请优先覆盖）

综合 §1.4 + §2 的"潜在失效点"列，AA 的对抗性审查应至少回答：

1. **HT-3**：`SegmentedConcurrency.tryAcquire` happy path 两次 O(16) 全段求和是否可合并/裁剪（高 limit 下预检值不值）。
2. **HT-4**：`lastEwmaUpdateMs` 额外 CAS 是否值这个正确性代价，能否降频（如仅重播种时写）。
3. **TS-3**：同 ms 压缩失败能否绕过熔断——量化跳闸所需失败数，评估是否需 α 下限或其它兜底。
4. **LT-4**：默认 `minCalls=1` 的假跳闸面是否需要加固。
5. **LC-3**：`startProbe/stopProbe` 的 `prev.join(2000)` 超时窗口是否会产生双探针。
6. **HT-5**：TLR 惰性分配是否真不会落在请求首调。
7. **TA-3/TA-4**：大步前跳与 uptime 环绕是否有未覆盖污染面。
8. **零分配**：`GovernanceException`/`CircuitBreakerCollector` 是否严格 off hot path。

> 对每条发现，务必给出 `文件:行` 证据与严重程度；对 §2 中"期望行为已成立"的场景，明确"已核实成立、无需改动"（防止盲目改动引入回归，lessons §13）。
