# 代码审查缺陷清单（AA 独立审查 · 增量验证 + 新发现）

**审查范围**：`src/main/java` 全部 17 个核心源文件 + 构建系统（Gradle 9.2.1 + Java 21）
**审查方法**：第一性原理分析 + 对抗性攻击模拟 + 状态机死锁/活锁排查 + 内存可见性/JMM 审查
**审查日期**：2026-08-04
**与既有报告的关系**：本报告**验证**了 `CODE_REVIEW_DEFECTS_AA.md` 的 7 个缺陷全部为真，并补充 5 个新缺陷（N1-N5）+ 1 个架构性观察（N6）。
**构建基线**：`./gradlew build` 成功，240 项测试全部通过（UP-TO-DATE 缓存命中）。

---

## 验证：既有报告缺陷（确认为真）

### ✅ 缺陷 1：Reactive 包装器将客户端 `CANCEL` 误判为业务失败（高）
- **确认方式**：代码分析 + Grep 验证测试盲区
- **验证结果**：
  - `CircuitBreakerOperator.java:31` 确认 `success = signal == ON_COMPLETE`（CANCEL → false）
  - `src/test/java/dev/circuitbreaker/reactive/` 目录 **无 CANCEL 测试**（Grep 无匹配），仅有 `CircuitBreakerOperatorTest` 覆盖 ON_COMPLETE/ON_ERROR
  - 攻击向量确认：恶意客户端批量 `wrap(...).subscribe().cancel()` 可污染 EWMA → DoS 式误跳闸
- **优先级确认**：高（可用性攻击面）

### ✅ 缺陷 2：探针选举非原子性，transition 与 probeGen.set 之间丢失 release（中）
- **确认方式**：并发控制分析
- **验证结果**：
  - `EwmaCircuitBreaker.java:49-56`：transition(CAS) 成功后 `probeGen.set(brGen(st.breakerState.get()))` 重新读一次 breakerState（TOCTOU）
  - 赢得选举的线程在窗口被抢占 → release 时 `brGen != probeGen` → 探针结果丢失 → 恢复延迟 ~2×openMillis
- **优先级确认**：中（可用性，非安全）

### ✅ 缺陷 3：SegmentedConcurrency 回滚风暴（中）
- **确认方式**：并发准入策略分析
- **验证结果**：
  - `SegmentedConcurrency.java:39-64`：乐观 increment-then-global-check 在 limit < SEG 时全局回滚
  - `PolicyBuilder.java:85-91` 仅 System.err 警告，不阻止使用
  - 攻击向量确认：突发并发 > limit → 全部回滚 → 吞吐崩塌/活锁
- **优先级确认**：中（可用性）

### ✅ 缺陷 4：EWMA 4.66h 环绕假跳闸（中偏低）
- **确认方式**：时间字段位宽计算
- **验证结果**：
  - `EwmaCircuitBreaker.java:115`：`lastUpdateMs` 20-bit，16ms 量化 → 2^20 × 16ms ≈ 4.66h
  - 模块减法 `(nowQ - ewLast) & EW_LAST_MASK` 在环绕点压回 ≈0
  - 周期性空闲（如夜间健康检查） + 旧高错误率 → 恢复流量后立即假跳闸
- **优先级确认**：中偏低（确定性触发，影响可接受）

### ✅ 缺陷 5：token version 10 位环绕（低）
- **确认方式**：位布局计算
- **验证结果**：
  - `TokenCodec.java:27`：`VERSION_BITS=10`，环绕窗口 = 1024 次热更新
  - 单次 RT 内 1024 次热更新在现实中不可能
- **优先级确认**：低（理论性）

### ✅ 缺陷 6：STATES 数组可见性不一致（低）
- **确认方式**：JMM 内存可见性分析
- **验证结果**：
  - `ResourceManager.java:14`：`STATES` 普通数组，`CONFIGS` 为 `AtomicReferenceArray`
  - `FlatExecutionEngine.java:25-34`：先读 `STATES[resourceId]`（普通读），后读 `CONFIGS.get(resourceId)`（volatile 读）
  - 理论上消费线程可能读到 `STATES[id]=null` 抛异常，重试后成功
  - 依赖"注册完成后才使用"的口头约定，非显式内存可见性保证
- **优先级确认**：低（窗口极窄）

### ✅ 缺陷 7：passCount 递增先于 encode（低）
- **确认方式**：异常安全分析
- **验证结果**：
  - `FlatExecutionEngine.java:53-54`：`passCount.increment()` 在 `TokenCodec.encode()` 之前
  - encode 的符号位溢出保护（L53）仅在异常输入时触发，但计数已 +1 → 指标失真
- **优先级确认**：低（防御性一致性问题）

---

## 🆕 新发现缺陷（既报告未覆盖）

### N1 [中] ResourceManager.state()/config() public 方法无边界检查

- **类型**：健壮性 / 安全（外部攻击面）
- **定位**：
  - `ResourceManager.java:48-54`（`state()` 和 `config()` 方法）
  - `CircuitBreakerCollector.java:36,58`（调用方使用外部数组 `resourceIds`）
- **严重程度**：中
- **第一性原理分析**：
  `STATES`/`CONFIGS` 数组大小固定为 1024（MAX_RESOURCES）。边界保护应由**数据中心**统一提供（如数据库 ID 校验），但公共 API 应至少做防御性检查，避免外部恶意输入穿透导致 unchecked 异常污染线程。
- **攻击场景**：
  ```java
  // Prometheus scrape 调用方传入外部数据
  CircuitBreakerCollector collector = new CircuitBreakerCollector(-1, 1024, 5000);
  // collect() 内部调用 ResourceManager.state(id) → ArrayIndexOutOfBoundsException
  ```
  负数或 ≥1024 的 id → `STATES[id]` 抛 `ArrayIndexOutOfBoundsException` / `CONFIGS.get(id)` 抛 `IndexOutOfBoundsException`（unchecked），可能击穿 Prometheus scrape 线程、监控面板等非热路径，导致观察系统崩溃。
- **测试盲区**：
  - 所有测试内部使用的 resourceId 都由 `ResourceManager.register()` 返回（保证 0-1023 范围），**未测试非法外部输入**。
  - Grep 结果：测试内仅调用 `ResourceManager.state(rid)`（rid 为 register 返回），无边界测试用例。
- **修复建议**：
  ```java
  public static ResourceState state(int resourceId) {
      if (resourceId < 0 || resourceId >= MAX_RESOURCES) {
          throw new IllegalArgumentException("resourceId out of range: " + resourceId);
      }
      return STATES[resourceId];
  }
  public static ResourceConfig config(int resourceId) {
      if (resourceId < 0 || resourceId >= MAX_RESOURCES) {
          throw new IllegalArgumentException("resourceId out of range: " + resourceId);
      }
      return CONFIGS.get(resourceId);
  }
  ```
  或在 `CircuitBreakerCollector` 构造时校验传入数组。

---

### N2 [中] ConfigSwapper.swap 未校验目标 resource 已注册（current==null 时静默写入空 slot）

- **类型**：一致性 / 状态机缺陷
- **定位**：`ConfigSwapper.java:21-39`
- **严重程度**：中
- **第一性原理分析**：
  `CONFIGS` 与 `STATES` 应成对存在（一个 resource 有且仅有一对 config/state）。swap 只操作 CONFIGS，但未校验对应的 STATES 是否存在。若对未注册的 resourceId 执行 swap，会导致 "CONFIGS 有值但 STATES 为 null" 的不一致状态。
- **失败场景**：
  ```java
  // 热更新服务 bug，传入错误的 resourceId（未注册）
  ResourceConfig newCfg = ...;
  ConfigSwapper.swap(999, newCfg);  // resourceId=999 未注册
  // swap 内部：current = null，跳过版本检查，CAS(null→newCfg) 成功
  ```
  结果：`CONFIGS[999] = newCfg`，`STATES[999] = null`。后续 `tryAcquire(999)` 抛 `IllegalArgumentException("unregistered resourceId: 999")` —— 配置已发布却无法使用，热更新静默失效（调用方可能认为成功，但实际上资源不可用）。
- **影响**：
  - 热更新时无报错但资源无法使用 → 运维难以察觉
  - 垃圾槽位累积（未注册的 id 被 config 占用） → 可能影响后续注册逻辑（`nextFreeId` 遍历查找时会跳过）
- **修复建议**：
  ```java
  public static void swap(int resourceId, ResourceConfig newConfig) {
      // 校验 resource 已注册
      if (ResourceManager.state(resourceId) == null) {
          throw new IllegalStateException("resource not registered: " + resourceId);
      }
      // ... 其余逻辑不变
  }
  ```
  或依赖调用方保证（在 swap 前检查），但建议防御性校验。

---

### N3 [中] SegmentedConcurrency.release 无幂等/下溢保护，double-release 使并发计数器变负、限制永久失效

- **类型**：防御性 / 状态机失效
- **定位**：`SegmentedConcurrency.java:66-68`
- **严重程度**：中（极端条件下可导致保护机制永久失效）
- **第一性原理分析**：
  计数器不变量：`sumConcurrency() >= 0`（非负）且 `<= concurrencyLimit + SEG`（允许 overshoot）。release 的 `decrementAndGet()` 假设调用方保证"同一 token 只释放一次"。若违反（double-release），计数器下溢为负。
- **攻击/故障场景**：
  ```java
  // 用户误用：既用 reactive wrapper 又手动 release
  long token = FlatExecutionEngine.tryAcquire(rid);
  Mono.just("ok")
      .doFinally(s -> FlatExecutionEngine.release(rid, token, true))  // 第一次
      .map(v -> {
          FlatExecutionEngine.release(rid, token, true);  // 第二次（误）
          return v;
      });
  ```
  或异常路径重入：业务代码在 try 块内手动 release，finally 又释放。

  两次 release → `concurrency[bucketIdx]` 从 1 → 0 → -1。后续 `sumConcurrency()` 返回负数（如 total=15，实际持有 16）。`total > limit` 检查失效（15 < 5 也通过），并发限制永久失效（灾难性失效模式：限制消失）。
- **测试盲区**：
  - Grep 测试代码：所有 release 测试都严格对应 try-finally，无 double-release 用例
  - `ConcurrencyStressTest` 有并发 acquire/release，但未测试 double-release 场景
- **修复建议**：
  ```java
  public static void release(ResourceState st, int bucketIdx) {
      AtomicInteger counter = st.concurrency[bucketIdx];
      for (;;) {
          int cur = counter.get();
          if (cur <= 0) {
              // 已为 0 或下溢 → 忽略（防御）或记录警告
              return;
          }
          if (counter.compareAndSet(cur, cur - 1)) {
              return;
          }
      }
  }
  ```
  或在 token 内增加 `released` 标志（需修改 TokenCodec 布局，代价较高）。

---

### N4 [中偏低] FlatExecutionEngine.release 用"当前 cfg"的 errThresholdPpm/minCalls 评估跳闸，但热更新可能已关闭 CB，使阈值退化为无效默认值 → 陈旧 token 误跳闸

- **类型**：热更新语义 / 状态机污染
- **定位**：
  - `FlatExecutionEngine.java:90-93`（release 读取当前 cfg）
  - `EwmaCircuitBreaker.java:82-96`（CLOSED 分支 re-check）
  - `PolicyBuilder.java:63-76`（默认值：errThresholdPpm=0, minCalls=1）
- **严重程度**：中偏低（业务不受影响，但状态/指标污染）
- **第一性原理分析**：
  release 评估跳闸时使用**当前 config 的阈值**（`cfg.errThresholdPpm` / `cfg.minCalls`）。若热更新关闭了 CB（新 `mask` 不含 `MASK_CIRCUIT_BREAKER`），`PolicyBuilder.build()` 跳过阈值校验，errThresholdPpm 退化为默认值 0，minCalls 退化为 1。陈旧 token（acquire 时启用了 CB）的 release 会用无效阈值评估，可能误跳闸。
- **失败场景（确定性）**：
  1. 初始 config：mask 含 CB，errThresholdPpm=500000 (50%)，minCalls=10
  2. 积累样本：10 次请求，ewmaState (gen=0, count=10, ppm=500000)
  3. 热更新关闭 CB：new config (mask 不含 CB, errThresholdPpm=0, minCalls=1, version=1)
  4. 有陈旧 token in flight（version=0, mask 含 CB）
  5. release(token, true)：
     - token version=0 != cfg.version=1 → verMatch=false → 不更新 EWMA
     - 进入 EwmaCircuitBreaker.release CLOSED 分支：
       - `ewGen(e)==gNow` (gen=0 无变化，匹配) ✓
       - `ewCount(e)>=minCalls(=1)` (10>=1) ✓
       - `ewPpm(e)>=errThresholdPpm(=0)` (500000>=0) ✓
       - transition(CLOSED→OPEN) → 误跳闸
  6. 结果：breakerState 被置 OPEN，但新 config.mask 不查 CB，业务不受影响；状态机错误，指标混乱（如 `CircuitBreakerCollector` 汇报 OPEN 状态但实际未熔断）
- **影响**：
  - 热更新时可能错误触发 transition（CLOSED→OPEN 或 OPEN→CLOSED 的陈旧 token release）
  - 指标/状态污染，误导运维
  - 若后续再次热更新开启 CB，breakerState 可能处于 OPEN 状态（需等 openMillis）
- **修复建议**：
  - 方案 A（推荐）：release 时若当前 `cfg.mask` 不含 `MASK_CIRCUIT_BREAKER`，跳过跳闸评估（仅更新 EWMA（若 verMatch）但不检查 trip）：
    ```java
    if ((cfg.mask & ResourceConfig.MASK_CIRCUIT_BREAKER) != 0) {
        EwmaCircuitBreaker.release(st, now, success, cfg, versionMatch);
    }
    ```
  - 方案 B：用 token 内嵌的 acquire-time cfg 阈值评估（需在 token 中存阈值，增大 token 位宽）
  - 方案 C：至少在 CLOSED re-check 前增加 `if ((cfg.mask & MASK_CIRCUIT_BREAKER) == 0) return;` 防御
- **与缺陷 1 的关系**：
  缺陷 1 是 semantic 不变（CANCEL 不应计入错误率）；本缺陷是热更新语义（陈旧 token 不应触发新策略跳闸）。两者都是 release 路径的语义问题。

---

### N5 [低] LazyTokenBucket.tryAcquire 的 nowMs 左移未防御超长期溢出（42位）

- **类型**：防御性 / 边界条件
- **定位**：`LazyTokenBucket.java:46`
- **严重程度**：低（理论上数十年后触发）
- **第一性原理分析**：
  `bucketState` 布局：高 42 位 `Time_last`，低 22 位 `Tokens`。`nowMs` 是相对毫秒（nowRelMs()），可能超过 2^42-1（≈139 年）。左移 22 位会溢出到符号位，污染 token 字段。
- **失败场景（确定性）**：
  ```java
  long nowMs = (2L << 42L) + 1000L;  // 超过 2^42，理论可行（运行 139 年）
  long next = (nowMs << TIME_SHIFT) | (nTok - 1);
  // (nowMs << 22) 溢出 → time 字段错误 → 后续 refill 计算 dtMs 偏离真实值
  ```
  虽然 `TokenCodec.encode()` 对 `timeMs` 有显式截断（`& TIME_MASK`），但 `LazyTokenBucket` 内部 `nowMs` 未截断，与 defensive 编码风格不一致。
- **影响**：
  - 超长期运行（>139 年）后，token bucket 状态机计算 dtMs 可能错误（但 real refill 仍能工作，因为 `nowMs - tLast` 在同溢出方向，差值可能正确）
  - 与 `TokenCodec.encode` 的显式掩码风格不一致，易误认为已知边界
- **修复建议**：
  ```java
  long next = ((nowMs & (TOKEN_MASK << TIME_SHIFT)) << TIME_SHIFT) | (nTok - 1);
  // 或更简单：显式掩码 nowMs 到 42 位
  long now42 = nowMs & (TOKEN_MASK << TIME_SHIFT);
  long next = (now42 << TIME_SHIFT) | (nTok - 1);
  ```
  或在文档中标注 "2^42 ms = 139 years 后未定义行为"（与 `TokenCodec` 的 27-bit = 37小时 标注一致）。

---

### N6 [低] SystemOverload 静态可变状态跨测试泄漏（升级为待修）

- **类型**：测试隔离性
- **定位**：`SystemOverload.java:16-21`（静态字段）
- **严重程度**：低（仅影响测试，不影响生产）
- **验证结果**：
  - `SystemOverload` 类有多个静态可变状态：`currentLevel`、`probeThread`、`probeRunning`、`stopProbe`、`SHED_PERMILLE`
  - 既有报告 CODE_REVIEW_DEFECTS_AA.md 的"次要观察"已提及，但未修复
  - 影响 `SystemOverload` 相关测试的隔离性（如 `SystemOverloadTest`、`SystemOverloadShedValidationTest`），测试间可能泄漏状态
  - **建议修复**：在 `@BeforeEach` 或 `@AfterEach` 重置所有静态字段（需添加测试专用 `resetForTest()` 方法）

---

## 优点确认（验证既有设计正确性）

1. **✅ 64-bit token 内嵌 resourceId（BR-053）+ token-mask 驱动 release**
   - 验证：`TokenCodec.java:12-17` 注释详细说明，`FlatExecutionEngine.java:76-82` 校验 `tokenRid != resourceId`
   - 价值：reactive 跨线程/跨资源释放防漂移严谨，防止数据污染

2. **✅ 代际防 ABA + 陈旧 EWMA 惰性 re-seed**
   - 验证：`EwmaCircuitBreaker.java:121-123` 检测 `ewGen(cur) != gNow` 时 re-seed
   - 价值：无需显式 clear CAS，防止陈旧 EWMA 污染新代际

3. **✅ @Contended + -XX:-RestrictContended 实测偏移验证 + 守护测试**
   - 验证：`ResourceState.java:23-26` 注释说明，`ContendedPaddingGuardTest` 守护 JVM 参数
   - 价值：防止 false sharing，实测从 4B 偏移到 cache-line 隔离

4. **✅ GovernanceException.fillInStackTrace() 返回 this，高频 block→throw 零栈轨迹开销**
   - 验证：`GovernanceException.java:50-53` 跳过栈轨迹捕获
   - 价值：控制流异常（非故障）不分配栈轨迹，热路径零分配

5. **✅ LazyTokenBucket 溢出安全（pathological 输入饱和处理）+ ms 粒度 refill（N1）+ 低 QPS 不饿死（BR-013）**
   - 验证：`LazyTokenBucket.java:68-84` refillTokens 有多重饱和检查
   - 价值：防御恶意输入，长空闲后仍能正常 refill

6. **✅ PolicySpec 跨参数 SLA 不变量前移到注册时**
   - 验证：`PolicySpec.java:189-242` 检查 Little's law、采样窗口、跳闸余量
   - 价值：运行时零开销，提前暴露配置错误

---

## 总体结论

**架构水准**：极高（热路径零分配、CAS 无锁、代际防 ABA、token 内嵌 resourceId），设计目标的创新性（config-state 分离、64-bit token 编码）已验证正确实现。

**缺陷分布**：
- 高优先级：2 个（缺陷 1：CANCEL 误判 + N1：边界检查攻击面）
- 中优先级：5 个（缺陷 2/3 + N2/N3/N4）
- 中偏低：1 个（缺陷 4）
- 低优先级：3 个（缺陷 5/6/7 + N5/N6）

**修复优先级（供 DA）**：
1. **N1**（中）+ **缺陷 1**（高）— 共同构成外部攻击面，优先修复 + 补对抗性测试
2. **N3**（中）— double-release 下溢可导致并发限制永久失效，防御性修复
3. **缺陷 2**（中）— probeGen 原子性，恢复延迟
4. **N2**（中）— ConfigSwapper 不一致状态，影响热更新可靠性
5. **缺陷 3**（中）— SegmentedConcurrency 回滚风暴，影响低 limit 吞吐
6. **N4**（中偏低）— 热更新语义，指标/状态污染
7. 缺陷 4/5/6/7 + N5/N6（低）— 合并到一轮清理

---

## 交付给 DA

- 缺陷清单：本报告
- 修复范围建议：
  1. **高/中优先级**（缺陷 1 + N1-N4）应在本迭代修复
  2. **中优先级**（缺陷 2/3）建议本迭代修复
  3. **低优先级**可合并到下一轮清理
- 修复后预期：
  - 所有高/中优先级缺陷修复 + 补充对抗性测试
  - `./gradlew build` 全通过
  - 代码覆盖率不降低（jacoco 报告）

**@DA-E2E 请接收缺陷清单并开始修复工作。**