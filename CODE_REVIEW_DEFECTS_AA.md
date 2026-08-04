# 代码审查缺陷清单（AA 独立审查 · 第一性原理 + 对抗性）

- **审查范围**：`src/main/java` 全部 17 个核心源文件 + 关键测试覆盖度交叉验证
- **审查方法**：第一性原理（断路器/限流/并发准入的核心不变量推导）+ 对抗性原理（攻击/滥用/边界场景模拟）
- **审查日期**：2026-08-04
- **与既有报告的关系**：本清单**不重复**既有报告（`CODE_REVIEW_REPORT_v2.md` / `FIX_SUMMARY.md`）已发现并修复的 `startProbe` 双 CAS 缺陷和测试侧问题，聚焦于**未被覆盖的、真实的生产代码缺陷**。
- **总体结论**：架构水准极高（热路径零分配、CAS 无锁、代际防 ABA、token 内嵌 resourceId），但在**reactive 语义、探针选举原子性、并发准入策略、时间字段环绕**四个维度存在既有报告遗漏的缺陷，其中 1 个高优先级（可被利用的可用性攻击）、3 个中优先级。

---

## 🔴 高优先级（建议上线前修复）

### 缺陷 1：Reactive 包装器将客户端 `CANCEL` 误判为业务失败，污染熔断器错误率（可被利用触发 DoS 式误跳闸）

- **类型**：正确性 / 安全（可用性攻击面）
- **定位**：`src/main/java/dev/circuitbreaker/reactive/CircuitBreakerOperator.java:30-33`
- **严重程度**：高
- **第一性原理分析**：
  熔断器 EWMA 错误率的不变量是"反映**下游服务健康度**"（业务失败、超时、连接拒绝）。客户端主动取消（`SignalType.CANCEL`）是**消费者侧**决策（用户离开、上游超时级联取消、调用方不再需要结果），**不反映下游服务是否健康**。把 CANCEL 计入错误率违反了该语义不变量。
- **对抗性场景**：
  ```java
  boolean success = signal == SignalType.ON_COMPLETE;   // CANCEL → false
  FlatExecutionEngine.release(resourceId, token, success);
  ```
  `doFinally` 在 `CANCEL` 时 `signal == CANCEL`，`success=false`，进入 `release(..., false)` → 当 mask 含 `MASK_CIRCUIT_BREAKER` 时 → `EwmaCircuitBreaker.release(ok=false)` → CLOSED 分支 `updateEwma(PPM_FAIL)`，错误率被推高。
  - **攻击向量**：恶意/故障客户端大量 `wrap(...).subscribe()` 后立即 `cancel()`，可人为把 EWMA 错误率推过 `errThresholdPpm`，触发**非下游故障的熔断跳闸**，使合法请求被 `BLOCK_CIRCUIT_BREAKER` 挡下 —— 一个无需打垮下游即可制造服务不可用的可用性攻击。
  - **真实误触发**：BFF/网关超时级联取消、用户主动取消、Reactor `timeout` 上游取消，都会污染错误率。
- **测试盲区**：`CircuitBreakerOperatorTest` 与 `EndToEndScenarioTest` 仅覆盖 `ON_COMPLETE` / `ON_ERROR`，**无 CANCEL 用例**，缺陷被测试设计掩盖。
- **修复建议**：
  区分"槽位释放"（必须做，防泄漏）与"错误率计入"（CANCEL 不应计入）。三态语义：
  ```java
  .doFinally(signal -> {
      // 槽位/concurrency 必须无条件释放（防泄漏）—— 但错误率语义需区分
      boolean success;
      switch (signal) {
          case ON_COMPLETE -> success = true;
          case CANCEL      -> success = true;   // 取消≠下游失败，不计入错误率（仍释放槽位）
          default          -> success = false;  // ON_ERROR 等真正失败才计
      }
      FlatExecutionEngine.release(resourceId, token, success);
  });
  ```
  若需更精确，可引入 `release` 的第三态（`cancelled`）让 EWMA 完全跳过该样本，concurrency 仍回滚。并补充 CANCEL 场景的对抗性测试。

---

## 🟡 中优先级（本迭代建议修复）

### 缺陷 2：探针选举的非原子性窗口 —— `transition` 与 `probeGen.set` 之间可丢失探针 release，恢复延迟一个 `openMillis` 周期

- **类型**：正确性 / 并发（状态机原子性）
- **定位**：`src/main/java/dev/circuitbreaker/core/breaker/EwmaCircuitBreaker.java:49-56`（选举侧）与 `70-81`（release 侧）
- **严重程度**：中
- **第一性原理分析**：
  "选举唯一探针"是一个临界区操作，由两个语义步骤组成：① `transition(OPEN→HALF_OPEN)`（CAS 成功者赢得选举）；② 记录该探针的代际 `probeGen = gen`，供 release 识别。这两个步骤**必须原子**才能保证"被选举的探针能被 release 识别"。当前实现拆成两条独立语句：
  ```java
  boolean won = transition(st, OPEN, HALF_OPEN, nowMs + cfg.openMillis); // ① CAS
  if (won) {
      st.probeGen.set(brGen(st.breakerState.get()));                     // ② 普通 set（还重新读了一次 breakerState）
  }
  return won;
  ```
- **失败场景**：
  赢得选举的线程在 ① 与 ② 之间被抢占（GC、CPU 调度、页缺失）。此时 `probeGen` 仍是旧值（≠ 当前 HALF_OPEN 的 gen）。该线程返回 `true`，调用方执行业务后 `release`：
  ```java
  if (s == HALF_OPEN) {
      if (brGen(b) == st.probeGen.get()) { ... }  // probeGen≠当前gen → 探针release被忽略
      return;
  }
  ```
  探针结果被丢弃，HALF_OPEN 卡住，直到 `brEnd`（选举时设的 `nowMs+openMillis`）超时，由 `tryAcquire` 的 HALF_OPEN 自愈分支转回 OPEN，再等一个 `openMillis` 才能重新选举探针。**恢复延迟最多 ~2×openMillis**，期间该资源对所有请求返回 `BLOCK_CIRCUIT_BREAKER`。
- **次要问题**：`probeGen.set(brGen(st.breakerState.get()))` 在 ① 之后**重新读** `breakerState`，存在 TOCTOU —— 读到的可能是其他线程刚做的下一次 transition 的 gen（如已被 release 解析为 CLOSED），写入错误的 `probeGen`。
- **修复建议**：
  让 `transition` 返回它实际写入的新 gen（CAS 成功路径已知 `gNext`），选举侧直接用该值，消除重读与窗口：
  ```java
  // transition 成功时返回 gNext（>0 或用 boxed/特殊值表示未赢）
  long newGen = transitionReturningGen(st, OPEN, HALF_OPEN, nowMs + cfg.openMillis);
  if (newGen >= 0) {
      st.probeGen.set(newGen);   // 用本线程刚写入的确定值，无窗口、无重读
      return true;
  }
  ```
  或将 `probeGen` 折叠进 `breakerState` 的保留位，使"选举"成为单次 CAS（彻底原子）。

### 缺陷 3：`SegmentedConcurrency` 在低 limit / 高突发并发下的全局回滚风暴（瞬时吞吐崩塌 / 活锁倾向）

- **类型**：性能 / 可用性（并发准入策略）
- **定位**：`src/main/java/dev/circuitbreaker/core/concurrency/SegmentedConcurrency.java:39-64`
- **严重程度**：中
- **第一性原理分析**：
  准入控制应采用**悲观额度检查**（先确认有额度再占用）。当前采用**乐观 increment-then-global-check**：先无条件自增段计数，再求和所有段，超限则回滚。这在"资源准入"语义下是误用 —— 乐观并发控制适合"冲突罕见"的场景，而并发准入的冲突（多个请求争抢最后几个名额）恰恰是**高频**的。
- **失败场景（limit < SEG，如 limit=5, SEG=16, limitPerSeg=1）**：
  16 个线程突发、各探测到不同段：每段 per-seg 检查 `0 < 1` 通过 → 全部自增到 1 → `total = 16 > 5` → **全部 16 个线程回滚、全部返回 -4**。瞬时并发从理论可放行的 5 降到 0。线程重试时若再次同步突发，重复回滚 → 活锁/饥饿，吞吐崩塌。
  - 即使 `limit` 在 `[SEG/2, SEG)` 区间，突发并发数远大于 limit 时也会出现大面积回滚。
  - `PolicyBuilder` 对 `concurrencyLimit < SEG` 仅打印 `System.err` 警告（L85-91），**不阻止**使用，且警告只讲 per-segment 碰撞，未提示全局回滚风暴。
- **正确性**：不会突破 limit（硬上限成立，`concurrentAcquireReleaseNeverNegative` 验证）；问题在**可用性/吞吐**。
- **修复建议**：
  - 方案 A（推荐）：在 increment 前先读 `total` 做悲观预检（`if (sumAllSegments() >= limit) return -1;`），再 increment；increment 后再做一次校验兜底回滚。预检把绝大多数超限请求挡在 increment 之前，消除回滚风暴。
  - 方案 B：维护一个全局 `LongAdder`/`AtomicInteger` 作为精确总额度（与分段计数并用），准入先对全局额度 CAS，成功再分发到段。代价是热路径多一次 CAS，但消除了 O(16) 求和与回滚。
  - 至少：在 `PolicySpec`/`PolicyBuilder` 中把 `concurrencyLimit < SEG` 从 WARN 升级为更醒目的约束，或在文档中明确"低 limit 下突发吞吐会显著低于 limit"。

### 缺陷 4：EWMA 时间字段 4.66h 环绕 —— 周期性长空闲后的陈旧错误率假跳闸（确定性触发，非概率）

- **类型**：正确性（时间字段位宽 / 边界）
- **定位**：`src/main/java/dev/circuitbreaker/core/breaker/EwmaCircuitBreaker.java:115-130`（`updateEwma` 的 `dtQ` 计算）
- **严重程度**：中（偏低）
- **第一性原理分析**：
  时间衰减 EWMA 的核心是准确的 `Δt`。`ewmaState.lastUpdateMs` 为 20-bit、按 16ms 量化存储（`nowMs>>4`），环绕周期 = 2²⁰ × 16ms ≈ **4.66 小时**。环绕点附近 `dtQ = (nowQ - ewLast) & EW_LAST_MASK` 会把真实的大间隔**取模压回到 ≈0**，导致 α≈0、错误率不衰减。
- **失败场景（确定性）**：
  注释以"τ≥1s → 概率可忽略"论证安全性，但这只对**随机间隔**成立。对**周期性空闲**（如夜间 0~6 点无请求的健康检查型资源、批处理间空闲 5h 的下游），间隔是**确定性**地落在 4.66h 之后：
  - 跳闸前曾积累高错误率（ppm ≥ 阈值），count ≥ minCalls；
  - 资源保持 CLOSED 但 4.66h+ 无请求，`ewmaState` 原样保留；
  - 恢复流量后首个请求：`dtQ≈0` → 不衰减 → 旧 ppm 仍 ≥ 阈值 → **立即假跳闸 CLOSED→OPEN**，把恢复后的正常流量挡在外面。
- **修复建议**：
  - 方案 A：在 `updateEwma` 检测到 `dtQ` 异常小但绝对 `nowMs` 跨度大时（需引入一个独立的宽位"上次绝对时间"或在 gen 不匹配外的"时间明显倒退/环绕"判定），强制 re-seed。
  - 方案 B（更稳）：把 lastUpdate 字段拓宽（如从 ewmaState 借位，或与 breakerState 的 `endTimeMs` 54 位字段共用一个宽位时间源），把环绕周期推到远超任何现实空闲（如 ≥ 数年）。
  - 方案 C：对超长 Δt（如 `dtMs > k×τ`，k≥8）直接判定为"已完全衰减"，强制 ppm 归零 re-seed（当前只在 gen 不匹配时 re-seed，时间长但 gen 仍匹配时不 re-seed）。

---

## 🟢 低优先级（可选 / 理论性）

### 缺陷 5：token version 10 位环绕窗口（1024 次热更新）—— 极端高频热更新下 stale token 版本重合

- **定位**：`src/main/java/dev/circuitbreaker/core/FlatExecutionEngine.java:91`，`TokenCodec.java:27`（`VERSION_BITS=10`）
- **严重程度**：低（理论性）
- **分析**：`versionMatch = (version == (cfg.version & VERSION_MASK))`。token 仅内嵌 version 低 10 位。若某次请求在 flight 期间该资源发生 **1024 次热更新**，新 config 的低 10 位会与 stale token 重合，`versionMatch=true`，stale release 的 EWMA 更新到错误代际。单次 RT 内 1024 次热更新在现实中近乎不可能，但应在文档中标注该环绕窗口为已知边界（与 `BR-052` 的 6→10 位扩宽决策一致地记录）。

### 缺陷 6：`ResourceManager.STATES` 为普通数组，发布可见性与 `CONFIGS` 不一致

- **定位**：`src/main/java/dev/circuitbreaker/core/ResourceManager.java:14, 20-32`
- **严重程度**：低
- **分析**：`CONFIGS` 用 `AtomicReferenceArray`（volatile 语义），`STATES` 却是普通 `ResourceState[]`。`register()` 内 `STATES[id]=st` 在 `synchronized` 块、且先于 `CONFIGS.set`；但消费侧 `FlatExecutionEngine.tryAcquire` 的读顺序是**先读 `STATES[id]`（普通读）、后读 `CONFIGS.get`（volatile 读）**。volatile 读只保证其**之后**的访问看到 volatile 写之前的所有写，而 `STATES` 读在它**之前**，不在保护范围内 —— 理论上消费线程可能读到 `STATES[id]=null`（旧值）抛 `IllegalArgumentException`，随后重试才成功。依赖"注册完成后才被使用"的口头约定，而非显式内存可见性保证。
- **修复建议**：将 `STATES` 改为 `AtomicReferenceArray`（或 volatile 数组语义），与 `CONFIGS` 对齐，把发布保证从"约定"提升为"机制"。

### 缺陷 7：`passCount` 递增先于 `TokenCodec.encode` —— encode 异常时计数与返回不一致

- **定位**：`src/main/java/dev/circuitbreaker/core/FlatExecutionEngine.java:53-54`
- **严重程度**：低
- **分析**：`st.passCount.increment()` 在 `return TokenCodec.encode(...)` 之前。若 `encode` 的符号位溢出保护抛 `IllegalStateException`（仅在 `timeMs`/`resourceId` 异常时触发），`passCount` 已 +1 但无 token 返回，导致 pass 计数与实际放行数不一致（指标失真）。正常输入下不会触发，属防御性一致性问题。
- **修复建议**：先 `encode` 到局部变量，再 `increment()`，最后 return；保证"计数与返回原子可见"。

---

## 次要观察（测试 / 文档侧，供 TA 参考）

- **`EndToEndScenarioTest.metricsExposedAfterTraffic`（L107）** filter 用 `s.name.equals("circuit_breaker_calls")`，但 `CircuitBreakerCollector` 实际 family name 为 `circuit_breaker_calls_total`（未注册到 registry、无 Prometheus 后缀自动补齐）。该 filter 可能匹配为空，使 `pass=0.0`、`assertThat(pass).isGreaterThan(0)` 失败或空转，未真正验证 pass 计数 —— 需 TA 核对测试是否实际生效。
- **`SystemOverload` 静态可变状态**（`currentLevel`/`probeThread`/`probeRunning`）在测试间泄漏，`@AfterEach` 仅重置 `SHED_PERMILLE`，未重置其余 —— 影响 `SystemOverload` 相关测试的隔离性。

---

## 优点（确认既有设计正确性）

- ✅ 64-bit token 内嵌 `resourceId`（BR-053）+ token-mask 驱动 release，reactive 跨线程/跨资源释放防漂移严谨。
- ✅ 代际（generation）防 ABA + 陈旧 EWMA 惰性 re-seed，`generationPreventsImmediateReTrip` 验证到位。
- ✅ `@Contended` + `-XX:-RestrictContended` 实测偏移验证 + 守护测试。
- ✅ 控制流异常 `fillInStackTrace()` 返回 `this`，高频 block→throw 零栈轨迹开销。
- ✅ `LazyTokenBucket` 溢出安全（pathological 输入饱和处理）、`N1` ms 粒度 refill、`BR-013` 低 QPS 不饿死。
- ✅ `PolicySpec` 把跨参数 SLA 不变量（Little's law、采样窗口、跳闸余量）前移到注册/热更新时，零热路径开销。

---

## 建议修复顺序（供 DA）

1. **缺陷 1**（高）— reactive CANCEL 语义，可被利用，优先修复 + 补对抗性测试。
2. **缺陷 2**（中）— probeGen 原子性，恢复延迟，影响可用性。
3. **缺陷 3**（中）— 并发准入回滚风暴，影响低 limit 场景吞吐。
4. **缺陷 4**（中偏低）— EWMA 环绕，周期性空闲场景假跳闸。
5. 缺陷 5/6/7（低）— 防御性/理论性，可合并到一轮清理。
