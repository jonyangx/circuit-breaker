## 用例：时间衰减 EWMA 熔断（Time-Decay EWMA Circuit Breaker）

### 1. 头部与元数据
* **用例 ID：** UC-005
* **用例名称：** 熔断判定与三态迁移（能力 0x01）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** FlatExecutionEngine（acquire 经 0x01 判定；release 经 0x01 上报与迁移）
* **目标：** 以时间衰减 EWMA + 三态状态机替代 Sentinel `LeapArray` 慢调用/异常比例统计，无锁、无后台定时器。
* **层级：** Sub-function

### 3. 上下文与触发器
* **触发器：** acquire（`mask & 0x01`）判放行/阻断；release（`mask & 0x01`）上报样本并可能触发迁移。
* **前置条件：** `breakerState`、`ewmaState` 就位。
* **后置条件：** 三态迁移每条边 `generation+1`；EWMA 按代际惰性重播种。

---

### 4. 场景与流程

#### 4.1 acquire 侧（惰性放行）
1. **熔断器：** 读 `breakerState`，解出 `state`、`generation`、`endTimeMs`。
2. **CLOSED：** 放行。
3. **OPEN：** 若 `now ≥ endTime`，CAS `OPEN→HALF_OPEN`（gen+1），唯一成功线程放行探路，其余阻断；若 `now < endTime`，阻断 `-2`。
4. **HALF_OPEN：** 仅允许有限探路（in-flight 门闩=1），其余阻断 `-2`。

#### 4.2 release 侧（状态迁移，v2 补齐）
1. **HALF_OPEN：** success → `transition(HALF_OPEN→CLOSED)`；fail → `transition(HALF_OPEN→OPEN, now+openMillis)`。（gen+1，旧代 EWMA 自动作废）
2. **CLOSED：** `updateEwma(now, success?0:1_000_000)`；若同代 ∧ `count≥minCalls` ∧ `ppm≥errThreshold` → `transition(CLOSED→OPEN, now+openMillis)`。（Use BR-020/022/023/024/025）

#### 4.3 异常流程
* **EWMA 代际不匹配：** `updateEwma` 读到 `ewmaGen != gNow` → 丢弃陈旧累积，用当前样本重播种（count=1, ppm=x, gen=gNow）。
* **CAS 自旋失败：** 重读重试。

#### 非功能性需求
* **性能：** α 计算禁止 `Math.exp`（用分段近似，热路径走 α≈u 无查表分支）（BR-021）；EWMA 用 ppm 定点整数（BR-022），避免 floatToIntBits CAS 抖动。

---

### 5. 其他要求
* **关键业务规则：** BR-020-ewma-time-decay、BR-021-alpha-piecewise、BR-022-ppm-fixed-point、BR-023-mincalls-threshold、BR-024-generation-aba、BR-025-state-machine（见 `rules.md`）
* **未决问题：** HALF_OPEN 探路门闩是否复用 breakerState 借位或独立字段（design §4.3.3 留两种实现）。
