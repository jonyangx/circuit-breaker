# AA 复验审查（R2）：极端 TPS 场景修复落地复核 + 补漏判定

> 角色：AA-REVIEW（架构设计 / 代码审查）
> 日期：2026-08-08（R2 复验轮）
> 对象仓库：`/Users/jon/opensource/circuit-breaker`（git HEAD `4770396`；`src/` 工作树与 HEAD 一致，唯一未提交改动为 `docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md`——SA v2 返工版）
> 上游基线：
> - `docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md`（SA 需求说明，**v2 返工版**，已过 QA 验收：§0b 本轮基线、§2 场景矩阵、§3 五门禁 14 条 AC）
> - `docs/qa/AA_EXTREME_TPS_REVIEW.md`（**前序 AA 基线产出**，文档头部标旧 HEAD `691377e`；本 R2 文档以当前 HEAD `4770396` 复核其结论落地状态）
> 审查方式：逐文件核对 4 处 P1/P2 修复源码证据 + 对应测试覆盖；全量 `./gradlew test`（**263 tests / 0 failures / 0 errors / 0 skipped**）作为无回归基线；P3 观察点与测试缺口逐条判定。每条证据 `文件:行`。

---

## 0. 结论摘要

本轮定位「**复验 + 补漏**」：不重复实施已落地改动，复核修复状态并决定补缺。

- **4 处 P1/P2 修复全部落地且无回归**（对照 263/0/0 全绿基线）。前序 AA 报告的 P1/P2 结论在 HEAD `4770396` 全部成立，无需回退或重改。
- **TS-3 最终跳闸侧测试已固化**，不再要求 TA 新增。
- **P3 观察点（E1/E2/E3）处置全部「维持记录」**，无新风险。
- **HT-3/HT-4 优化后性能影响评估**：HT-3 happy path 33→17 次 volatile 读 + 1 写，全局强制不变；HT-4 每次 committed update 写锚点是 R1 必需（不可降频），AA §2.2 建议的可选「同 ms 同值写跳过」微优化**未实施**，正确性无影响，建议延后。
- **真实测试缺口判定：无必补项**。SA §2 残留的 4 个「无专项」行（HT-2 / HT-6 / LC-5 / TA-4）经核实：
  - **HT-2 / HT-6**：非正确性缺口（性能属性，JMH / LongAdder 固有设计），不补；
  - **LC-5**：代码正确成立，测试注入需 seam，价值低，可选不补；
  - **TA-4**：**实际已覆盖**（`TokenCodecDefectTest.rtMsCorrectWhenTimeFieldWrapsAcrossUptime` + `EwmaCircuitBreakerTest.wrapAliasedPeriodicIdleDoesNotFreezeStalePpm`）——SA 文档 TA-4 行「无 uptime 级回绕专项」为**陈旧标记**（与 SA 已返工的 TS-3/TA-3/LC-3/HT-3 四行同类但漏改）。
- **本轮唯一新发现（LOW，doc-only）**：SA §2 TA-4 行「已有覆盖」列需补正为已覆盖，与已返工四行保持一致。
- **对前序 AA 报告头部基线决策**：**不改写**前序 `AA_EXTREME_TPS_REVIEW.md`（其结论为 `691377e` 基线的历史审计记录；SA 已明确不作「已同步当前 HEAD」宣称，避免误导）。本轮结论独立落于本 R2 文档，以当前 HEAD `4770396` 为审查对象。

**设计不变量（SA §3.2）全部保持**：热路径无 `Math.exp`、无 `synchronized`（`HotPathGuardTest` 2/0/0 绿）、零堆分配、单 `AtomicLong` 不自 stripe、`@Contended` 布局由 `ContendedPaddingGuardTest` 强制。

---

## 1. 审查对象与证据来源

- 已落地修复源码（4 处）：
  - `src/main/java/dev/circuitbreaker/core/PolicyBuilder.java:19-24`（P1 [LT-4] minCalls 默认值）
  - `src/main/java/dev/circuitbreaker/core/system/SystemOverload.java:95-126 / 156-166`（P2 [LC-3] 双探针：身份校验 + interrupt）
  - `src/main/java/dev/circuitbreaker/core/concurrency/SegmentedConcurrency.java:53-64`（P2 [HT-3] 条件化预检）
  - `src/main/java/dev/circuitbreaker/core/breaker/EwmaCircuitBreaker.java:139-191`（P2 [HT-4] R1 锚点提交语义）
- 测试证据：`TpsDynamicsBreakerTest` / `TpsDynamicsConcurrencyTest` / `SystemOverloadTest` / `TpsDynamicsTokenBucketTest` / `PolicyBuilderTest` / `PolicyBuilderValidationTest` / `TokenCodecDefectTest` / `EwmaCircuitBreakerTest` / `SegmentedConcurrencyGlobalLimitTest` / `HotPathGuardTest` / `ContendedPaddingGuardTest`。
- 基线事实：`./gradlew test` → 263/0/0（本机复跑确认；`build/test-results/test/` 43 个 suite 聚合）。
- 设计事实源：`docs/brd/design.md`、`docs/system/07_ALGORITHM_DEEP_DIVE.md`（同 SA 引用）。

---

## 2. 4 处 P1/P2 修复落地复验（含回归证据）

### 2.1 [P1][LT-4] 默认 `minCalls=1` → `10` —— ✅ 已修复，无回归

| 项 | 证据 |
|---|---|
| 源码 | `PolicyBuilder.java:24` `private int minCalls = 10;`；`:19-23` 注释声明风险（对齐 S5 WARN 线 ≥10，违反 ERROR 线 <3） |
| 校验链 | `PolicyBuilder.java:80-85` 仍保留 `minCalls` 正数/≤65535 校验；`:102-104` 构造 `ResourceConfig` 传入新默认 |
| 回归测试 | `PolicyBuilderTest.defaultMinCallsMeetsS5ColdStartFloor`（`:52-69`）：断言默认 `minCalls ≥ 3` 且 S5 不报 ERROR；suite 6/0/0 绿 |
| 行为推演 | 冷启动/长空闲后首失败：dt≈uptime → α≈0.63+ → ppm≈630k，但 `count=1 < minCalls=10` → 不跳闸（`EwmaCircuitBreaker.java:115-117` 跳闸条件 `count≥minCalls ∧ ppm≥threshold` 的 `count` 侧不满足） |
| 回归风险 | 低 TPS 高失败率资源需 ≥10 样本才跳闸——这是 S5 WARN 线的设计权衡，`minimumCalls(int)` 可显式下调；所有既有测试显式设 `minimumCalls` 或构造 `ResourceConfig` 直传，无依赖旧默认 1 的用例 |

> **LOW 观察（非缺陷）**：该回归测试为「默认值 ≥3 + S5 不报 ERROR」的**静态值断言**，弱于 AA §2.4 原建议的「默认 builder 冷启动首失败不跳闸」**行为断言**。逻辑上 `count=1<10` 必然不跳闸（值断言已覆盖根因），行为断言为可选增强，非必补。

### 2.2 [P2][LC-3] 双探针竞态 —— ✅ 已修复，无回归

| 项 | 证据 |
|---|---|
| 源码-身份校验 | `SystemOverload.java:101` finally 中 `if (probeThread == Thread.currentThread()) probeRunning.set(false);` —— 旧线程退出时若 `probeThread` 已被新探针接管，不清新探针的 running 标志（`probeThread` 先于 join 在新线程启动序列中赋值 `:111`，故旧线程 finally 判断必然为 false） |
| 源码-interrupt | `SystemOverload.java:118-126` startProbe 的 `prev.join(PROBE_JOIN_TIMEOUT_MS)` 超时后 `if (prev.isAlive()) prev.interrupt();`；`:156-162` stopProbe 对称 force-wake |
| 时序正确性 | `probeThread = t`（`:111`）→ join 旧线程 → `if (stopProbe)` 复核（`:131-134`）→ `t.start()`（`:136`）。join 期间新线程未启动，stopProbe 中途调用被 `stopProbe` 复核拦截；t 启动后 `probeLoop` 首个 `while(!stopProbe)` 即退出，无泄漏。竞态窗口封口 |
| 回归测试 | `SystemOverloadTest.rapidStopStartCyclesDoNotLeakProbeThreads`（`:97-112`，6 次循环后 ≤1 探针线程）；`interruptedProbeExitsViaInterruptPathAndRestartsCleanly`（`:115-144`，确定性演练 interrupt→退出→running 标志清除→重启干净）；`probeLifecycleStartsAndStopsWithoutThrowing`（`:49`）+ `stopProbeAndWaitThenStartProbePreventsDualProbeRace`（`:58-75`）；suite 8/0/0 绿 |
| 覆盖现状 | 前序 AA §2.5 指出的「无 join(2000) 超时路径覆盖」**已由 `interruptedProbeExitsViaInterruptPathAndRestartsCleanly` 补上**（不等待真实 2s，直接 interrupt 等价触发） |

### 2.3 [P2][HT-3] 并发段预检条件化 —— ✅ 已实施，无回归

| 项 | 证据 |
|---|---|
| 源码 | `SegmentedConcurrency.java:62` `if (limitPerSeg <= 2 && st.sumConcurrency() >= cfg.concurrencyLimit) return -1;`；`:53-61` 注释量化（happy path 33→17 次 volatile 读 + 1 写），`:68-78` 后检 + rollback 仍是精确兜底 |
| 边界 | `limitPerSeg = ceil(concurrencyLimit/SEG)`：limit ≤ 32 时预检启用（防 rollback storm）；limit > 32 时预检跳过（高 limit 已饱和概率极低，16 读预检多为白费） |
| 全局强制不变 | 后检 `for(16) sum > limit → decrementAndGet + return -1`（`:70-78`）与 release CAS-loop 防负（`:88-96`）均未动；AA §2.1 的 AC-9「过冲 ≤ SEG + 有界」由 per-segment cap 兜底 |
| 回归测试 | 高 limit 专项：`TpsDynamicsConcurrencyTest.highLimitSkipsPrecheckButStillEnforcesGlobalLimitWithinBoundedOvershoot`（`:75-119`，limit=100→limitPerSeg=7>2 预检跳过；断言 peak ≤ limit+SEG、blocked>0、sum 归零）；低/紧 limit 路径：`SegmentedConcurrencyGlobalLimitTest` 42/0/0 全绿；suite 3/0/0 绿 |

### 2.4 [P2][HT-4] EWMA 锚点提交语义 —— ✅ 正确性代价保留，无回归

| 项 | 证据 |
|---|---|
| 源码 | `EwmaCircuitBreaker.java:188` `st.lastEwmaUpdateMs.set(nowMs);`（仅 `ewmaState.compareAndSet` 成功后退位提交）；`:142-151` R1 绝对差空闲检测 `(nowMs - absLast) >> 3 >= ewmaTauMs ∧ ≥100ms` |
| 锚点语义 | 每次 **committed update** 写绝对锚点（不可降频——若只在 re-seed 时写，`absLast` 停于旧值，后续 update 把真实间隔误判 long-idle → 持续 re-seed → count 恒 1 → minCalls 永远凑不齐 → 熔断失效）；`ResourceState.java:49` 初始 -1，首样本回退 modular 守卫 |
| 覆盖 | `EwmaCircuitBreakerTest.wrapAliasedPeriodicIdleDoesNotFreezeStalePpm`（`:173-184`，nowMs=2²⁴+2000 别名周期空闲不冻结陈旧 ppm）固化了 R1 的必要性 |
| 可选微优化（未实施） | AA §2.2 建议的「同 ms 同值写跳过」（`nowMs != absLast` 守卫）**未实施**（`:188` 仍无条件 `set`）。正确性无影响（同值重复写是 cache-line 写争用的微优化项）；本轮「复验+补漏」不改热路径，建议延后。字段未 `@Contended`（与 `probeGen` 共享尾部 cache line，但 `probeGen` 仅 HALF_OPEN 低频读写，影响有限） |

---

## 3. P3 观察点处置状态（E1 / E2 / E3）

| # | 级别 | 前序状态 | HEAD 4770396 复核 | 处置 |
|---|---|---|---|---|
| E1 | P3 | `decodeTime`/`rtMs` 死代码（`TokenCodec.java:60-84`），生产无调用 | grep 实证：`src/main` 仅 `TokenCodec.java` 自身定义 + javadoc 引用；仅 `TokenCodecDefectTest`/`TokenCodecTest` 使用。生产 `FlatExecutionEngine` 无调用 | **维持记录**（SA §0b「⏸ 维持记录」一致）；删除属 API 破坏（测试依赖），接入 RT 观测无需求，不改 |
| E2 | P3 | 直构 `new ResourceConfig(...)` 绕过容量校验 | `ResourceConfig.java:23-35` 构造器仍无校验；builder 侧守卫已加（`PolicyBuilder.java:63-72` qps/capacity ≤ 22 位 TOKEN_FIELD_MAX）+ `PolicyBuilderValidationTest` 5 条容量用例全绿 | **维持记录**（builder 是推荐入口；直构属内部/测试用途，`seed()` 静默 clamp 已文档化） |
| E3 | P3 | version 10 位回绕（1024 次热换）陈旧 token 可能匹配新 cfg.version | `TokenCodec.java:9/27-32` javadoc 明确文档化「BR-052 ABA window」；`TokenCodecDefectTest.versionFieldIs10Bits`（`:139-146`）固化 10 位截断；跳闸仍需 count≥minCalls ∧ ppm≥threshold，单陈旧样本不致误跳闸 | **维持记录**（已文档化权衡，无需改动） |

---

## 4. HT-3 / HT-4 优化后性能影响评估

- **HT-3**（`SegmentedConcurrency.java:53-64`）：高 limit（>32）下预检跳过，happy path 从 33 次 volatile 读 + 1 写降至 **17 次读 + 1 写**（约省一半读，AA §2.1 量化一致）。全局强制不变（后检精确）。高 limit 专项测试确认过冲 ≤ limit+SEG、release 不为负、无泄漏。**性能收益落地，无正确性代价。**
- **HT-4**（`EwmaCircuitBreaker.java:188`）：每次 committed update 多一次 `AtomicLong.set`——这是 R1 绝对锚点的**必要代价**（防 wrap-aliased periodic idle 冻结陈旧 ppm），不可回退、不可降频。同 ms 多线程重复写同一值产生 cache-line 写争用，但属微优化级（可选延后项），不影响纳秒级数量级。**正确性代价成立，性能影响有限。**
- **JMH 兜底**：本轮无热路径改动，AC-12「改动热路径须复跑 `./gradlew jmh`」**不触发**（DA 无必做改动）。若后续实施「同值写跳过」，须复跑 jmh + ContendedPaddingGuardTest 复核。

---

## 5. 真实测试缺口判定（下游指令依据）

SA §2 场景矩阵残留的 4 个「无专项」行逐条判定：

| 场景 | SA 标记 | 复核事实 | 判定 |
|---|---|---|---|
| **HT-2** 时钟开销专项 | 无专项；JMH 覆盖 | `ClockSource.nowRelMs()` 单次 `nanoTime/1M` long 除法（`ClockSource.java:25-27`）；性能属性无正确性角度；单元微基准易 flaky | **非真实缺口，不补** |
| **HT-6** 高阻断率 LongAdder | 无专项 | `ResourceState.java:28-29` `LongAdder` 固有设计（AA §4 已核实无热 Cell 争用）；高并发阻断测试需确定性注入，价值低 | **非真实缺口，不补** |
| **LC-5** 探针异常 | 无专项 | `SystemOverload.java:181-189`：InterruptedException 退出、VirtualMachineError 传播、`catch(Throwable ignore)` 探针不死；代码简单且正确。测试注入需 seam（probeLoop 直接调 `ManagementFactory.getPlatformMXBean`，无注入点） | **正确性成立；测试可选**（如需补，需 DA 加 seam，价值低） |
| **TA-4** uptime 回绕 | 无 uptime 级回绕专项 | **已覆盖**：`TokenCodecDefectTest.rtMsCorrectWhenTimeFieldWrapsAcrossUptime`（`:92-101`，2²⁷ wrap 后 RT 模减精确=153ms）+ `EwmaCircuitBreakerTest.wrapAliasedPeriodicIdleDoesNotFreezeStalePpm`（`:173-184`，EWMA lastUpdateMs 2²⁴ 别名空闲） | **已覆盖；SA 文档 TA-4 行为陈旧标记** |

> **结论：无真实必补测试缺口。** TS-3（最终跳闸侧）、TA-3（大步前跳）、LC-3（探针竞态）、HT-3（高 limit 并发段）、TA-4（uptime 回绕）在 HEAD `4770396` 均已有专项覆盖并通过；HT-2/HT-6 为非正确性属性；LC-5 正确性成立且测试价值低。

---

## 6. 新发现（本轮唯一）

| # | 级别 | 文件:行 | 发现 | 说明 |
|---|---|---|---|---|
| F1 | LOW（doc-only） | `docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md` §2 TA-4 行「已有覆盖」列 | **陈旧标记**：SA v2 返工将 TS-3/TA-3/LC-3/HT-3 四行补为「已覆盖」，但 **TA-4 行仍标「无 uptime 级回绕专项」**；实际 `TokenCodecDefectTest.rtMsCorrectWhenTimeFieldWrapsAcrossUptime`（HEAD `4770396` 随修复提交）已提供 uptime 级回绕专项 | 与 SA 已返工四行同类；建议 SA 补正该行「已有覆盖」列（如「`TokenCodecDefectTest.rtMsCorrectWhenTimeFieldWrapsAcrossUptime`（:92-101）+ `EwmaCircuitBreakerTest.wrapAliasedPeriodicIdleDoesNotFreezeStalePpm`」），并相应检查 §3 AC-2 收窄清单是否需要含 TA-4。**纯文档一致性问题，非代码缺陷，不阻塞** |

---

## 7. 交接指令（给 DA / TA / SA）

### DA（编码实现）
- **无必做改动**（本轮 0 新 P0/P1/P2 代码缺陷）。
- **可选延后**：HT-4「同 ms 同值写跳过」微优化（`EwmaCircuitBreaker.java:188` 加 `nowMs != absLast` 守卫）——正确性无关，若实施须复跑 `./gradlew jmh` + `ContendedPaddingGuardTest`（AC-12）。
- 不得无故改动 §4「已核实成立」列表中的任何行为（lessons §13 防盲目改动回归）。

### TA（测试执行）
- **无必做新增**：TS-3 / TA-3 / LC-3 / HT-3 / TA-4 均已专项覆盖并通过（证据见 §2/§5）。
- **可选**：LC-5 若 DA 提供注入 seam（如测试钩子），可补「探针 catch Throwable 不死 + VirtualMachineError 传播」回归；无 seam 则跳过。
- 本轮 `./gradlew test` 已全绿（263/0/0），可直接作为 TA→QA 交接证据；若后续 DA 实施可选微优化，则改动后重跑并附报告。

### SA（需求文档）
- **补正 F1**：§2 TA-4 行「已有覆盖」列更新为已覆盖（引用 `TokenCodecDefectTest.rtMsCorrectWhenTimeFieldWrapsAcrossUptime` + `EwmaCircuitBreakerTest.wrapAliasedPeriodicIdleDoesNotFreezeStalePpm`）；评估 §3 AC-2 收窄清单是否纳入 TA-4。

---

## 8. 设计不变量复核（SA §3.2 门禁）

| 不变量 | 复核结果 |
|---|---|
| AC-4 HotPathGuardTest 保持绿 | ✅ `HotPathGuardTest` 2/0/0（热路径无 `Math.exp`、无 `synchronized`） |
| AC-5 热路径零堆分配 | ✅ 本轮无热路径改动；`@Contended` 布局由 `ContendedPaddingGuardTest` 1/0/0 强制（JDK 21 需 `-XX:-RestrictContended`，ResourceState 头部注释已文档化） |
| AC-6 单 AtomicLong + 自旋、不 stripe、TLR probe 路由 | ✅ 令牌桶/EWMA/breakerState 均单 AtomicLong；并发段 TLR 路由未变 |
| AC-7 配置/状态分离、token 位布局不变 | ✅ `STATES` 不随 `CONFIGS` 重建；TokenCodec 位布局（sign/time/version/bucket/resourceId/mask）未动 |

---

## 9. 前序 AA 报告基线决策说明

前序 `docs/qa/AA_EXTREME_TPS_REVIEW.md` 头部标注旧 HEAD `691377e`，其 §2/§3 结论已在本轮 HEAD `4770396` 复核落地（证据见 §2 与 SA §0b）。**决策：不改写前序报告**——它是对 `691377e` 基线缺陷清单的历史审计记录，改写会破坏审计追溯；SA 已在需求文档头部明确「不作已同步当前 HEAD 宣称」，避免误导。本轮全部结论独立落于本文档，供 DA/TA/QA 以 HEAD `4770396` 为对象引用。

---

*复验完成。4 处 P1/P2 修复全部落地、无回归；无真实必补测试缺口；唯一发现为 SA 文档 TA-4 行陈旧标记（F1，doc-only）。下游：DA 无必做改动，TA 无必做新增，SA 补正 F1 后即可进入 QA 最终验收。*
