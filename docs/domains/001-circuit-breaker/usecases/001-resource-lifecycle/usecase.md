## 用例：资源与生命周期（Resource & Lifecycle）

### 1. 头部与元数据
* **用例 ID：** UC-001 / UC-002 / UC-003
* **用例名称：** 注册资源与治理策略 / 获取治理令牌 acquire / 释放并上报调用结果 release
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** 集成该库的中间件/业务开发工程师（Developer）
* **次要参与者：** FlatExecutionEngine、ResourceManager
* **目标：** 以纳秒级开销、零分配地完成一次流量治理的「获取令牌 → 执行业务 → 释放上报」全生命周期。
* **层级：** User-Goal

---

### UC-001 注册资源与治理策略

* **触发器：** 系统初始化阶段，开发者调用 `ResourceManager.register(name, policy)`。
* **前置条件：** constitution 已锁定技术栈；资源数 < 1024。
* **后置条件（成功保证）：** 分配全局唯一整数 `resourceId`；`CONFIGS[resourceId]` 与 `STATES[resourceId]` 就位；`PolicyBuilder` 产物转不可变 `ResourceConfig`（含 version）。

#### 主成功场景
1. **开发者：** 以 `PolicyBuilder` 声明能力与参数（rate/capacity/errThreshold/minCalls/ewmaTau/concurrencyLimit）。
2. **ResourceManager：** 扫描现有编号，分配下一个 `resourceId`。（Use BR-001）
3. **ResourceManager：** 构造不可变 `ResourceConfig`（含初始 version），写入 `CONFIGS[resourceId]`。
4. **ResourceManager：** 构造稳定 `ResourceState`（bucketState/breakerState/ewmaState/concurrency[]/LongAdder），写入 `STATES[resourceId]`。（Use BR-002）
5. **ResourceManager：** 返回 `resourceId` 供业务持有。

#### 异常流程
* **5a. 资源数超限：** `resourceId ≥ 1024` → 抛出注册异常（容量超限）。

---

### UC-002 获取治理令牌 acquire

* **触发器：** 业务调用前调用 `FlatExecutionEngine.tryAcquire(resourceId)`。
* **前置条件：** 资源已注册。
* **后置条件：** 返回 `long`：≥0 为携带 time/version/bucketIdx/mask 的 token；<0 为阻断码。

#### 主成功场景
1. **Engine：** 系统过载前置短路检查（UC-007），命中返回 `BLOCK_SYSTEM_OVERLOAD(-1)`。
2. **Engine：** 读取 `config=CONFIGS[resourceId]`、`state=STATES[resourceId]`。
3. **Engine：** 取 `config.mask`，按位与依次判定：0x01 熔断(UC-005)、0x02 限流(UC-004)、0x04 并发(UC-006)；任一失败返回对应负阻断码（-2/-3/-4）。（Use BR-005）
4. **Engine：** 全部通过，打包 `[0][time41][version6][bucketIdx4][mask12]` 生成 token 返回。（Use BR-003）

#### 异常流程
* **3a. 被熔断：** 返回 `BLOCK_CIRCUIT_BREAKER(-2)`。
* **3b. 被限流：** 返回 `BLOCK_RATE_LIMITER(-3)`。
* **3c. 并发超限：** 返回 `BLOCK_CONCURRENCY(-4)`。

#### 非功能性需求
* **性能：** 单线程 P50 < 100 ns；0 字节堆分配（BR-NFR-perf）。时钟统一 `nanoTime/1M - START`（Use BR-006）。

---

### UC-003 释放并上报调用结果 release

* **触发器：** 业务调用结束（成功或失败）在 `finally` 中调用 `FlatExecutionEngine.release(resourceId, token, success)`。
* **前置条件：** 已获取有效 token（≥0）。
* **后置条件：** 并发段回滚、EWMA 上报、熔断状态机按需迁移、pass/block 计数递增。

#### 主成功场景
1. **Engine：** 从 token 解码 `version`、`bucketIdx`、`mask`、`time`。
2. **Engine：** 并发控制按 `bucketIdx` 回滚同一段 `-1`（UC-006）。
3. **Engine：** 若 mask 含 0x01：EWMA 上报（success=0, fail=1_000_000 ppm），并按状态机执行 release 侧迁移（UC-005）。
4. **Engine：** 版本校验：token.version 与 `CONFIGS[resourceId].version` 比对（Use BR-version-check，见 006）。
5. **Engine：** pass/block LongAdder 异步递增（UC-010）。
6. **Engine：** 计算 `rtMs = (now - decodeTime(token)) & TIME_MASK` 供观测。

#### 异常流程
* **4a. 版本已变（热更新发生在 acquire 与 release 之间）：** 并发计数照常按 bucketIdx 回滚（作用在稳定 STATES，永远正确）；EWMA 上报降权或跳过，避免旧阈值污染新配置。

#### 非功能性需求
* **性能：** P50 < 50 ns；0 字节堆分配。release **不依赖执行线程**（reactive 安全，见 UC-009）。

---

### 5. 其他要求
* **关键业务规则：** BR-001-resource-id-int、BR-002-config-state-separation、BR-003-token-encoding、BR-004-block-code-negative、BR-005-bitmask-dispatch、BR-006-monotonic-nanotime（详见同目录 `rules.md`）
* **未决问题：** `resourceId` 容量 1024 是否可配置（当前以 1024 为上限）。
