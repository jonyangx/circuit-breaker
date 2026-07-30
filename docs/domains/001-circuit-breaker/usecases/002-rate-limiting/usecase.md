## 用例：惰性令牌桶限流（Lazy Token Bucket）

### 1. 头部与元数据
* **用例 ID：** UC-004
* **用例名称：** 令牌桶限流判定（QPS 限流，能力 0x02）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** FlatExecutionEngine（在 UC-002 acquire 中经 bitmask 0x02 调用）
* **目标：** 以惰性时间推导、单 AtomicLong CAS 维持全局 QPS 上限，替代 Sentinel 时间窗口统计与后台漏桶。
* **层级：** Sub-function

### 3. 上下文与触发器
* **触发器：** UC-002 主流程第 3 步，`config.mask & 0x02 != 0`。
* **前置条件：** 资源已注册，`bucketState` 就位。
* **后置条件（成功）：** 成功扣减 1 令牌；失败返回 `BLOCK_RATE_LIMITER(-3)`。

---

### 4. 场景与流程

#### 4.1 主成功场景
1. **限流器：** 读 `bucketState`，解包为 `Time_last`、`Tokens_current`。（Use BR-010）
2. **限流器：** 取 `Time_now`（BR-006）。
3. **限流器：** `Tokens_add = (Time_now - Time_last) × ratePerMs`；`Tokens_new = min(capacity, Tokens_current + Tokens_add)`。（Use BR-011）
4. **限流器：** 若 `Tokens_new ≥ 1`，`Tokens_new -= 1`；重新打包 CAS 更新，失败自旋重试。
5. **限流器：** 返回放行（交还 UC-002 继续）。

#### 4.2 异常流程
* **4a. 令牌不足（`Tokens_new < 1`）：** 不推进 `Time_last`（Use BR-013），仅回写扣减后令牌数，返回 `BLOCK_RATE_LIMITER(-3)`。
* **4b. CAS 自旋失败：** 回到步骤 1 重读重试（无锁自旋）。

#### 非功能性需求
* **性能：** 纳秒级、零分配、单 AtomicLong CAS（禁止 synchronized）。
* **正确性不变量：** 全局 QPS 上限不可被分段破坏（Use BR-012）。

---

### 5. 其他要求
* **关键业务规则：** BR-010-token-bucket-layout、BR-011-lazy-refill、BR-012-no-stripe-bucket、BR-013-float-zero-fix（见 `rules.md`）
* **未决问题：** 超高 QPS（>50k）下 CAS 颠簸的实测峰值是否需要 @Contended 填充（已在 design §6.1 提议）。
