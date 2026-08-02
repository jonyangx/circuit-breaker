## 业务规则：时间衰减 EWMA 熔断（Time-Decay EWMA Circuit Breaker）

* **子领域：** 熔断（Circuit Breaking）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 业务规则目录

#### 2.2 计算规则
| 规则 ID | 规则名称 | 计算公式/算法 | 适用用例 | 来源 |
|---------|---------|--------------|---------|------|
| BR-020-ewma-time-decay | 时间衰减 EWMA | `α = 1 − exp(−Δt/τ)`；`EWMA_t = EWMA_{t-1} + α·(X_t − EWMA_{t-1})`；X_t=成功0/失败1；Δt 来自 ewmaState.lastUpdateMs | UC-005 | design §4.3.1 |
| BR-021-alpha-piecewise | α 分段近似 | u=Δt/τ：u≤1/128→α≈u（热路径，无查表）；1/128<u<8→LUT(512 段)+线性插值；u≥8→α=1。全程无 Math.exp | UC-005 | design §4.3.1.1 |
| BR-022-ppm-fixed-point | 错误率 ppm 定点 | 错误率用 ppm 整数(0..1_000_000)存储；`applyDecay` 用 `Math.round(α·(x−ewma))` 避免向零截断 | UC-005 | design §4.3.1/§4.3.2 |

#### 2.1 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-023-mincalls-threshold | 冷启动门槛 | count 字段用作 `minCalls` 门槛（样本不足前禁止跳闸），饱和于 65535 | UC-005 | design §4.3.2 |
| BR-025-state-machine | 三态状态机 | CLOSED/OPEN/HALF_OPEN；唯一改 generation 的入口是 transition()；探路门闩保证 HALF_OPEN 至多一个在途探路；**HALF_OPEN 探路截止（endTime=进入时刻+openMillis）过期则惰性回退 OPEN 重选探路——丢失探路自愈，无定时器（代码实现更新，对抗性审查 A1）** | UC-005 | design §4.3.3 |

#### 2.3 推断规则
| 规则 ID | 规则名称 | 规则逻辑 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-024-generation-aba | 代际消除 ABA | If ewmaState.generation ≠ breakerState.generation → Then 丢弃陈旧 EWMA 累积，用当前样本重播种（等价"进 CLOSED 清零"，无需显式清零 CAS） | UC-005 | design §4.3.3 |

**详细规则说明：**

##### BR-020-ewma-time-decay
* **类型：** 计算（Computation）
* **描述：** 复用时间差的**时间衰减**（非样本数衰减），Δt 越大衰减越充分，解决低 QPS 资源 EWMA 陈旧、高低 QPS 行为不一致。
* **计算公式：** `α = 1 − exp(−Δt/τ)`，`EWMA += α·(X_t − EWMA)`。
* **边界条件：** Δt=0 → α=0（同毫秒多样本不衰减）；见 BR-021 大 Δt 饱和。
* **关联用例：** UC-005

##### BR-021-alpha-piecewise
* **类型：** 计算（Computation）
* **描述：** 逐请求 `Math.exp` 在纳秒预算下不可接受（~20–40ns）；按 u 分三段，热路径命中 `α≈u`（相对误差<0.4%），查表仅稀疏/突发触发（误差 ppm 级）。
* **输入：** `dtMs`、`tauMs`
* **输出：** `float alpha`
* **伪代码（示意）：**
```java
static float alpha(long dtMs, double tauMs) {
    double u = dtMs / tauMs;
    if (u <= 1.0/128) return (float) u;        // 热路径
    if (u >= 8.0)      return 1.0f;            // 完全衰减
    double x = u * INV_STEP; int idx = (int) x; double f = x - idx;
    return (float)(1 - (EXP_LUT[idx]*(1-f) + EXP_LUT[idx+1]*f)); // LUT 插值
}
```

##### BR-024-generation-aba
* **类型：** 推断（Inference）
* **前提（If）：** `ewmaGen(cur) ≠ gNow`（breaker 已迁移）。
* **结论（Then）：** 重播种 EWMA（count=1, ppm=xPpm, last=now, gen=gNow）。
* **为什么 4 位（16 代）足以防 ABA：** 陈旧更新要误判为同代，需亚微秒窗口内 breaker 迁移满 16 次回到同值——真实时钟下不可能；即便撞上，仅单个样本误归代，被 EWMA 自平滑吸收。
* **关联用例：** UC-005

##### BR-025-state-machine
* **类型：** 约束（Constraint）
* **描述：** `transition(st, from, to, endTimeMs)` 是唯一改 generation 的入口；gen++ 即令旧代 EWMA 作废。
* **约束内容：** 每条迁移边隐含 `generation+1`；HALF_OPEN→CLOSED 无需显式清 EWMA。
* **违反后果：** HALF_OPEN→CLOSED 后旧高错误率立即二次跳闸，熔断器永远出不来。

### 3. 约束条件
* **位布局：** ewmaState `[gen:8][lastUpdateMs:20][count:16][ppm:20]`；breakerState `[state:2][gen:8][endTimeMs:54]`。lastUpdateMs 20 位（≈17.5min）足够：更长间隔 u≥8 已饱和。

### 4. 成功标准
* 三态迁移闭环：模拟连续失败→OPEN→到期→HALF_OPEN→探路成功→CLOSED，EWMA 不被旧值二次跳闸。
* α 近似误差：u∈(1/128,8) 段绝对误差 ≤ ~3e-5（ppm 级）。

### 5. 规则依赖
```
BR-006-monotonic-nanotime → BR-020-ewma-time-decay(Δt)
BR-020-ewma-time-decay → BR-021-alpha-piecewise
BR-020-ewma-time-decay → BR-022-ppm-fixed-point
BR-024-generation-aba ← BR-025-state-machine(transition gen++)
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |
