# AA 对抗性代码与设计审查：极端 TPS 场景（第一性原理 + 对抗性）

> 角色：AA-REVIEW（架构设计 / 代码审查）
> 日期：2026-08-08
> 对象仓库：`/Users/jon/opensource/circuit-breaker`（git HEAD `691377e`，工作树干净）
> 上游基线：`docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md`（SA 需求分析，5 门禁 14 条 AC）
> 审查方式：逐文件阅读全部 20 个核心源码 + 8 组相关测试 + 设计文档；逐条证据 `文件:行`。

---

## 0. 结论摘要

对 SA 需求分析 §4 给出的 **8 个优先核查点**逐条完成第一性原理 + 对抗性核查。结论：

- **确认 1 个 P1 缺陷**（LT-4 默认 `minCalls=1` 假跳闸面）；
- **确认 2 个 P2 缺陷**（LC-3 `join(2000)` 双探针竞态窗口；HT-4 `lastEwmaUpdateMs` 同 ms 重复写）；
- **确认 1 个 P2 性能优化建议**（HT-3 双次 O(16) 求和可条件化裁剪）；
- **确认 1 个 P2 测试缺口 + 量化结论**（TS-3 压缩失败收敛到 ~0.693τ，需补"最终跳闸"侧）；
- **确认 4 项"已核实成立、无需改动"**（HT-5 TLR 惰性分配、TA-3/TA-4 时间异常、零分配 off hot path、HT-4 正确性代价本身）。
- **3 个 P3 观察点**（`rtMs`/`decodeTime` 死代码、`ResourceConfig` 直构绕过容量校验、version 10 位回绕窗口）。

设计不变量（SA §3.2）**全部保持成立**：热路径无 `Math.exp`、无 `synchronized`（HotPathGuardTest 保持绿）、零堆分配成立、单 `AtomicLong` 不自 stripe、token 位布局未变。

---

## 1. 审查范围与证据来源

- 数据面热路径：`FlatExecutionEngine` / `LazyTokenBucket` / `EwmaCircuitBreaker` / `EwmaAlpha` / `SegmentedConcurrency` / `SystemOverload` / `TokenCodec` / `ClockSource`。
- 控制面生命周期：`ResourceManager` / `ConfigSwapper` / `PolicyBuilder` / `PolicySpec` / `CircuitBreakerOperator` / `CircuitBreakerCollector` / `ResourceState` / `ResourceConfig`。
- 测试证据：`TpsDynamicsBreakerTest` / `TokenCodecDefectTest` / `SegmentedConcurrencyGlobalLimitTest` / `TpsDynamicsConcurrencyTest` / `ConcurrencyStressTest` / `SystemOverloadTest` / `SystemOverloadShedValidationTest` / `PolicyBuilderTest` / `PolicyBuilderValidationTest` / `HotPathGuardTest` / `HotReloadDisableLeakTest`。
- 设计事实源：`docs/brd/design.md`（§3.2 / §4 纳秒级 / 零分配 / 无锁化目标）。

---

## 2. 8 个优先核查点逐条结论

### 2.1 [HT-3] SegmentedConcurrency happy path 双次 O(16) 求和 —— P2 性能优化（非缺陷）

**证据**：`core/concurrency/SegmentedConcurrency.java:56`（预检 `st.sumConcurrency() >= cfg.concurrencyLimit`）+ `:64-67`（后检 `for` 全段求和）。

**第一性原理分析**：
- happy path 原子读序列：`:49` 单段读（1）→ `:56` 预检（16）→ `:61` incrementAndGet（1 原子写）→ `:64-67` 后检（16）＝ **33 次 volatile 读 + 1 写**。
- 预检（注释 `:53-56`）的唯一目的是"limit 紧时的 rollback storm 防护"：在全局已满时于 increment 之前拒绝，避免 increment→sum→decrement 写风暴。
- 当 **limit 宽**（`limitPerSeg > 2`，即 `concurrencyLimit > 32`）时，全局满的概率极低，预检 16 次读几乎全部白费；此时后检 + 偶发 rollback（罕见）成本更低。

**量化**：高 TPS（>50k/s）下，33 次 volatile 读约为单次治理开销的最大单项；条件化后可降为 17 次读 + 1 写（约省一半）。

**处置建议**（DA 可选实施，属性能优化非正确性修复）：
```java
// 仅当 limit 较紧时启用预检（避免 rollback storm）；高 limit 时后检+rollback 已足够且罕见
int limitPerSeg = (int) Math.ceil(cfg.concurrencyLimit / (double) ResourceState.SEG);
if (st.concurrency[bidx].get() >= limitPerSeg) return -1;
if (limitPerSeg <= 2 && st.sumConcurrency() >= cfg.concurrencyLimit) return -1; // 条件化预检
st.concurrency[bidx].incrementAndGet();
...
```
**测试现状**：`SegmentedConcurrencyGlobalLimitTest` / `TpsDynamicsConcurrencyTest` 均用 limit∈{1,2,3,5,10,100}，无高 limit 下预检开销的覆盖；改动后需 AC-12（JMH 不劣化）复核。

**严重程度**：P2（性能优化建议；正确性由后检 `:68-72` 兜底，AC-9"过冲≤SEG+有界"已满足）。

---

### 2.2 [HT-4] `lastEwmaUpdateMs` 每次 release 额外写 —— 正确性代价成立 + P2 微优化

**证据**：`core/breaker/EwmaCircuitBreaker.java:142`（读 `absLast`）、`:181`（CAS 成功后 `st.lastEwmaUpdateMs.set(nowMs)`）；`core/ResourceState.java:49`（字段定义，未加 `@Contended`）。

**第一性原理分析**：
- R1 绝对时钟检测是 **wrap-aliased periodic idle 的唯一探测器**（`EwmaCircuitBreaker.java:135-141` 注释）：20 位 modular `lastUpdateMs` 把 true gap = k·2²⁴ms+ε 的周期性空闲别名成 dt≈0，modular 守卫看不见它，只能靠绝对差。因此该字段**必须随每次 committed update 推进，不能简单降频**——若只在 re-seed 时写，`absLast` 停留在旧值，任何后续 update 都会把 `nowMs-absLast` 误判为 long-idle，导致持续 re-seed（count 恒为 1，minCalls 永远凑不齐，熔断失效）。**HT-4 的"每次 release 写"是 R1 正确性的必要代价。**
- 剩余优化空间：**同 ms 内 `nowMs` 相同**，多线程 release 会对该字段重复写同一值（高压同 ms 压缩失败场景）。可加 `nowMs != absLast` 守卫跳过同值写（读 1 次替换写 1 次，净省 cache-line 写争用）。
- 字段未 `@Contended`：与 `probeGen`（`ResourceState.java:37`）共享尾部 cache line。`probeGen` 仅 HALF_OPEN 过渡时低频读写，影响有限；但高压 release 写 `lastEwmaUpdateMs` 会 invalidate 该行。可评估加 `@Contended`（需同步 `ContendedPaddingGuardTest`）。

**处置建议**：保持"每次 committed update 写"的语义（不可降频）；DA 可选实施同值写跳过 + 评估 `@Contended`。

**严重程度**：P2（微优化；正确性代价本身已核实成立、无需回退）。

---

### 2.3 [TS-3] 同 ms 压缩失败能否绕过熔断 —— 量化结论：不能绕过，收敛到 ~0.693τ

**证据**：`core/breaker/EwmaCircuitBreaker.java:174-178`（α 低通 + count 饱和 `:175`）、`:108-110`（跳闸条件 count≥minCalls ∧ ppm≥threshold）；`EwmaAlpha.java:36-38`（同 ms dt=0 → α=0）。

**第一性原理量化**：
- 同 ms 内只有第一个失败样本有 dt≥1ms（α≈1/τ），后续同 ms 样本 dt=0、α=0，**ppm 不变但 count 累积**。这是设计意图的低通滤波（`TpsDynamicsBreakerTest` §9.1 微突发阻尼）。
- ppm 爬升：`p_{n+1} = p_n + (1/τ)(1M - p_n)`（每 ms 至少 1 个失败样本时）。从 0 爬到阈值 T 的 ms 数：
  `n = ln(1 - T/1M) / ln(1 - 1/τ) ≈ 0.693τ`（T = 500k 时）。
  - τ=500ms → ~350ms；τ=1s → ~700ms；τ=5s → ~3.5s。
- **结论**：攻击者用高并发快速失败（≥1/ms 失败率）**无法绕过熔断**——持续 ~0.693τ 后必跳闸；且更高并发不加速跳闸（每 ms 有效样本仍 1 个）。微突发（≤数十同 ms）被阻尼是特性，不是漏洞。真正的暴露面是**响应延迟 = 0.693τ**，与设计"错误率在半衰期 τ 上反映"的语义一致。
- **测试缺口**：`TpsDynamicsBreakerTest.microBurstFailuresDampenedByLowAlpha`（`:38-56`）只覆盖"50 次不跳闸"侧，**未覆盖"持续压缩失败 >0.693τ 最终跳闸"侧**。SA AC-2 / AC-11 已要求补此侧 + 量化文档化。

**处置建议**：无需改代码（低通是设计意图）；TA 补"持续同 ms 压缩失败跨 τ 地平线最终跳闸"回归（旧逻辑已成立，测试用于固化边界）；AA 本结论写入文档作为 AC-11 的量化依据。

**严重程度**：P2（测试缺口 + 量化文档化；非正确性缺陷）。

---

### 2.4 [LT-4] 默认 `minCalls=1` 假跳闸面 —— **P1 缺陷**

**证据**：`core/PolicyBuilder.java:19`（`private int minCalls = 1;`）；`core/breaker/EwmaCircuitBreaker.java:108-110`（跳闸条件 `ewCount(e) >= cfg.minCalls && ewPpm(e) >= cfg.errThresholdPpm`）；`core/PolicySpec.java:237-249`（S5：`minCalls < 3` = ERROR，`< 10` = WARN）；`core/PolicyBuilder.java:103-113`（S5 仅 `.sla()` 显式调用时生效，opt-in）。

**第一性原理分析**：
- 冷启动首样本或 >τ 空闲后首样本的 dt≈uptime，`α ≈ 1 - e^(-uptime/τ)`；uptime≥τ 时 α≥0.63，单失败样本直接置 ppm≈630k（`EwmaCircuitBreaker.java:174-177`）。配合 `minCalls=1`：`count=1 ≥ 1 ∧ ppm≥threshold` **单失败立即跳闸**（阻断 openMillis，默认 5s，`PolicyBuilder.java:20`）。
- 触发面：(a) **冷启动首请求即失败**；(b) **长时间空闲（>τ）后首失败**——这正是 SA 场景 LT-1/LT-2 的核查区域，低 TPS 场景的高发路径。
- **不一致**：库自身的策略规范 S5 将 `minCalls<3` 判为 ERROR，但默认值 1 却违反该线，且默认路径（不显式 `.sla()`）完全不触发 S5。
- 现有测试全部显式设置 `minimumCalls`（`PolicyBuilderTest` `:16/:61/:76/:91`），**默认 minCalls=1 的行为零覆盖**。

**处置建议**（DA 实施，需回归）：
1. 默认 `minCalls` 由 1 提升至 ≥3（对齐 S5 ERROR 线），或直接 10（S5 WARN 线）；
2. 若为兼容性保留 1，则必须在 `enableCircuitBreaker` 的 javadoc 与 `PolicySpec` 中显式声明该风险，并要求生产显式设置 `minimumCalls`；
3. 补回归：默认 builder（`.enableCircuitBreaker(x)` 不接 minimumCalls）冷启动首失败**不跳闸**（旧代码下失败、新代码下通过，lessons §3）。

**严重程度**：**P1**（默认配置高影响假跳闸面 + 与自身 S5 策略不一致，修复成本低）。

---

### 2.5 [LC-3] `startProbe/stopProbe` 的 `join(2000)` 超时双探针竞态窗口 —— **P2 缺陷**

**证据**：`core/system/SystemOverload.java:96-102`（`prev.join(2000)` 超时后**仍** `t.start()` `:111`）、`:86-88`（旧线程 finally `probeRunning.set(false)`）、`:94-95`（`probeThread = t` 在 join **之前**已覆盖为旧值）。

**竞态链**：
1. `stopProbe()` 后立即 `startProbe()`：`stopProbe=true` 已设，旧线程卡在 `Thread.sleep(1000)`/`getCpuLoad`（GC STW 或 OS 调度暂停 >2s）未退出；
2. `startProbe` 的 `prev.join(2000)` 超时，**仍启动新线程** `:111` → 双探针同时写 `SHED_PERMILLE`；
3. 旧线程醒来发现 `stopProbe=true` 退出，finally `:87` 执行 `probeRunning.set(false)`——**错误清掉新探针的 running 标志**；
4. 下一次 `startProbe` 的 CAS `:78` 又能成功 → 第三探针启动 → 探针数量在极端暂停下可无界增长。

**覆盖现状**：`SystemOverloadTest` `:58-94` 已覆盖**正常退出路径**（join 快速返回）下的 stop→start 竞态与 start 幂等，但**无 join(2000) 超时路径**（旧线程存活 >2s）的覆盖——SA 场景 LC-3 标记"无专项"。

**处置建议**（DA 实施，需回归）：
1. 旧线程退出时用**身份校验**：`if (probeThread == Thread.currentThread()) probeRunning.set(false);`——新探针启动后 `probeThread` 已指向新线程，旧线程不再清标志；
2. `startProbe` 在 `prev.join(2000)` 超时后 `prev.interrupt()` 唤醒旧线程（`probeLoop` `:148-150` 已处理 InterruptedException 退出）；
3. 补回归：模拟旧线程卡死（如测试反射替换 `probeLoop` 阻塞体或缩短 join 超时）→ stop→start 后无双探针、`probeRunning` 不丢失。

**严重程度**：P2（触发条件苛刻——需旧线程 >2s 未退出；后果为双探针写 + running 标志错乱）。

---

### 2.6 [HT-5] TLR 惰性分配是否落于请求首调 —— 已核实成立、无需改动

**证据**：`core/system/SystemOverload.java:29`（`shed > 0 && ThreadLocalRandom.current()...` **短路成立**：SHED_PERMILLE=0 默认不触发 TLR）；`core/concurrency/SegmentedConcurrency.java:44`（TLR 每线程首调一次性分配，不可消除）。

**第一性原理分析**：
- `maybeShed` 的 TLR 调用被 `shed>0` 短路，**默认过载档位 0 时请求热路径不触达 TLR**（符合 SA HT-5 期望"SHED_PERMILLE=0 则不应触发 TLR"）。
- `SegmentedConcurrency` 的 TLR 是 probe 路由的必要机制（BR-031），每线程首调分配一个 `ThreadLocalRandom` 实例（~64B），此后 `current()` 仅读线程字段。线程池预热后稳态零分配。
- **P3 观察点**：若未来迁移虚拟线程（每请求一线程），`TLR.current()` 将退化为每请求分配，需在虚拟线程创建处预热。当前目标（复用线程池/事件循环）下不适用。

**处置建议**：无需改动。TA 可用 JMH `-prof gc` 固化"稳态 tryAcquire/release 分配为 0"（SA AC-5）。

**严重程度**：已核实成立、无需改动（附 P3 前瞻观察）。

---

### 2.7 [TA-3 / TA-4] 大步前跳与 uptime 环绕 —— 已核实成立、无需改动

**证据**：
- TA-3：`core/ratelimit/LazyTokenBucket.java:37`（`dtMs=Math.max(0,…)` clamp）、`:41`（`nTok=min(capacity, tok+add, TOKEN_MASK)` **饱和封顶**，大步前跳 token 桶不风暴）；`EwmaCircuitBreaker.java:171-172`（大步前跳触发 re-seed）。前跳后 `tLast=nowMs`（`:46`），后续 nowMs 正常递增，令牌按 ms 粒度平滑补充，**无 token 风暴、无状态污染**。
- TA-4：`core/TokenCodec.java:60-84`（`decodeTime`/`rtMs` 模减，`(now - decode) & TIME_MASK`）；**生产代码（`FlatExecutionEngine`）无任何 `decodeTime`/`rtMs` 调用**（grep 实证：仅测试使用）→ 27 位时间字段回绕**不影响任何生产逻辑**；`TokenCodecDefectTest:71-83` 显式断言 27 位截断行为。EWMA `nowQ` 20 位 modular 减 + R1 绝对差（nanoTime 292 年）均正确。

**处置建议**：无需改动。TA 可补 TA-3（大步前跳后 token 数/ppm/tLast 不越界、无风暴）与 TA-4（uptime 级回绕后 RT 模减正确）专项测试，固化为回归（SA AC-8）。

**严重程度**：已核实成立、无需改动（附 P3 死代码观察，见 §3）。

---

### 2.8 零分配 off hot path —— 已核实成立、无需改动

**证据**：`FlatExecutionEngine.tryAcquire/release` 全原始类型 + long 局部变量，无 `new`；`core/GovernanceException.java:50-53`（`fillInStackTrace()` 返回 `this`，阻断时由调用方主动 materialize，**off hot path**）；`observability/CircuitBreakerCollector.collect():40-83`（scrape 路径分配 ArrayList/Sample，**off 请求路径**）；`reactive/CircuitBreakerOperator.java:25-44`（`Mono.defer`/`doFinally` 闭包是 Reactor 每订阅机制，非治理热路径额外分配）。

**处置建议**：无需改动。

**严重程度**：已核实成立、无需改动。

---

## 3. 额外发现（超出 SA 8 核查点，P3 可维护性/前瞻）

| # | 级别 | 文件:行 | 发现 | 说明 |
|---|---|---|---|---|
| E1 | P3 | `core/TokenCodec.java:60-84` | `decodeTime`/`rtMs` 是**死代码**：生产无调用者，仅测试使用。 | `rtMs` 被 SA TA-4 引用为"release 侧 RT"，但 release 路径实际未接入 RT 度量。不影响正确性；建议要么接入（若需 RT 观测）要么删除/注释降噪。`TokenCodecDefectTest` 已文档化 27 位限制。 |
| E2 | P3 | `core/ResourceConfig.java:23-35` | 直接 `new ResourceConfig(...)` 可**绕过** `PolicyBuilder` 的 `capacity <= TOKEN_FIELD_MAX` 校验（`PolicyBuilder.java:65-67`），`LazyTokenBucket.seed()`（`:24-26`）会静默 clamp 到 22 位。 | 设计上 builder 是推荐入口；直构属内部/测试用途。可考虑在 `seed()` 或 `ResourceManager.register` 增加防御性断言，或文档化。 |
| E3 | P3 | `core/TokenCodec.java:26-33` / `ResourceConfig.java:21` | version 10 位回绕（1024 次热换）后，陈旧 token 的 `decodeVersion` 可能匹配新 `cfg.version`（`FlatExecutionEngine.java:94`）→ 旧样本喂入新 EWMA。 | 属已文档化的 BR-052 ABA 权衡；跳闸仍需 count≥minCalls ∧ ppm≥threshold，单陈旧样本不致误跳闸。无需改动，记录即可。 |

---

## 4. 已核实成立、无需改动（防盲目改动回归，对齐 lessons §13）

以下 SA §2 场景经源码逐行核实**行为正确**，DA 不得无故改动：

- **LT-1** 空闲后 token 饱和到 capacity、tLast 推进（`LazyTokenBucket.java:41/46`）；42 位 tLast 回绕（≈139 年）与 292 年 nowRelMs 无关。
- **LT-2** 空闲后首成功 α=1 全衰减清 ppm（`EwmaCircuitBreaker.java:171-172` re-seed）；R1 绝对锚点补 modular 别名漏洞（`:135-144`）。
- **LT-3** 抹零对策成立：`nTok<1` 时不推进 tLast（`LazyTokenBucket.java:42-44`）。
- **LT-5** 小 τ 时 `EW_IDLE_RE_SEED_FLOOR_MS=100` 绝对下限防止 count 永凑不齐（`EwmaCircuitBreaker.java:171`）。
- **HT-1** 单 AtomicLong CAS 自旋有界（`LazyTokenBucket.java:33-50`）；`ConcurrencyStressTest` 覆盖无活锁。
- **HT-6** LongAdder 计数无热 Cell 争用（`ResourceState.java:28-29`）。
- **TS-1/TS-2** 突发≤capacity 封顶、随后立即阻断；ms 粒度恢复 + 抹零语义（S6 已在 `PolicySpec:140-147` 文档化 qps 非 1000 倍数欠放）。
- **TS-4** OPEN↔HALF_OPEN 稳定振荡；probeGen 防陈旧 release hijack（`EwmaCircuitBreaker.java:63/94`）；HALF_OPEN 自我修复 re-arm（`:72-75`）。
- **TS-5** ppm 爬升/下降速率匹配 τ。
- **TS-6** token 内嵌 mask/version/resourceId 回滚成立（`FlatExecutionEngine.java:90-96`）；ConfigSwapper 版本单调 CAS（`ConfigSwapper.java:38-52`）。
- **TA-1** 时钟回拨 dt clamp 0 → 阻断不污染（`LazyTokenBucket.java:37`）。
- **TA-2** 负 dt modular 减巨大 → α=1 全衰减重播种（`EwmaCircuitBreaker.java:152` + `TpsDynamicsBreakerTest.clockReversalWithin20BitDoesNotCorruptEwma`）。
- **TA-5** `onCpuSample` 无时间参数，天然免疫墙钟跳变（`SystemOverload.java:33-47`）。
- **LC-1** `STATES.set` 先于 `CONFIGS.set`（volatile 序，`ResourceManager.java:30/34`）；AtomicReferenceArray volatile 发布。
- **LC-2** swap CAS 循环版本单调（`ConfigSwapper.java:38-52`）。
- **LC-4** 热换关闭 CB 后陈旧 token release 开头 `mask & MASK_CIRCUIT_BREAKER==0 → return`（`EwmaCircuitBreaker.java:85-87`）。
- **LC-5** probeLoop catch Throwable + 传播 VirtualMachineError（`SystemOverload.java:151-156`）。
- **HT-4 正确性代价本身**：`lastEwmaUpdateMs` 每次 committed update 写是 R1 必需（见 §2.2），**不可回退**。

---

## 5. 交接建议（给 DA / TA）

### DA 实施优先级
1. **P1**：`PolicyBuilder.java:19` 默认 `minCalls` 提升至 ≥3（对齐 S5 ERROR 线），补"默认配置冷启动首失败不跳闸"回归（旧代码失败/新代码通过）。
2. **P2**：`SystemOverload.java` 双探针修复——旧线程退出身份校验 `probeThread == Thread.currentThread()`；`join(2000)` 超时后 `prev.interrupt()`。
3. **P2（可选）**：`EwmaCircuitBreaker.java:181` 同 ms 同值写跳过（`nowMs != absLast` 守卫）；评估 `lastEwmaUpdateMs` 加 `@Contended`。
4. **P2（可选，性能）**：`SegmentedConcurrency.java:56` 预检条件化（`limitPerSeg <= 2` 才预检）。
5. **P3（可选）**：死代码 `rtMs/decodeTime` 清理或注释；`seed()` 防御性断言。

> ⚠ 热路径改动须复跑 `./gradlew jmh` 对比基线（SA AC-12）；正确性修复以 `docs/lessons.md` §3 为准（旧代码失败→新代码通过）。

### TA 测试补充（对齐 SA AC-2/AC-8/AC-11）
1. **TS-3 最终跳闸侧**：持续同 ms 压缩失败 >0.693τ 最终跳闸（量化边界固化）。
2. **LT-4**：默认 builder 冷启动首失败不跳闸 + 首成功清 ppm。
3. **LC-3**：join 超时路径双探针回归（无泄漏、probeRunning 不丢失）。
4. **TA-3/TA-4**：大步前跳 token 数/ppm/tLast 不越界、无风暴；uptime 级回绕 RT 模减正确。
5. **HT-3/HT-5**：高 limit 并发段过冲 ≤SEG；稳态 tryAcquire/release 零分配（JMH `-prof gc`）。

---

## 6. 设计不变量复核（SA §3.2 门禁）

| 不变量 | 复核结果 |
|---|---|
| AC-4 HotPathGuardTest 保持绿 | ✅ 热路径无 `Math.exp`（EwmaAlpha LUT 排除）、无 `synchronized`（仅 register setup） |
| AC-5 热路径零堆分配 | ✅ 全程无 `new`；TLR 每线程一次性；GovernanceException off hot path |
| AC-6 单 AtomicLong + 自旋、不 stripe、TLR probe 路由 | ✅ 全部保持 |
| AC-7 配置/状态分离、token 位布局不变 | ✅ STATES 不随 CONFIGS 重建；token 布局未动 |

---

*审查完成。缺陷清单与"已核实成立"声明见上；量化结论（TS-3 ≈0.693τ、LT-4 单失败跳闸面、HT-3 33 次读）供 DA/TA 实施与测试引用。*
