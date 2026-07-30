## 业务规则：惰性令牌桶限流（Lazy Token Bucket）

* **子领域：** 限流（Rate Limiting）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 业务规则目录

#### 2.1 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-010-token-bucket-layout | 桶状态位布局 | `bucketState` AtomicLong：高 42 位 Time \| 低 22 位 Tokens（≈139 年 / ≤4,194,303 QPS）。**capacity/qps ≤ 2²²−1（4,194,303）：refill 路径 nTok 对 TOKEN_MASK 取 min 防止 token 位溢出污染 Time 字段；PolicyBuilder 拒绝 qps>4,194,303（代码实现更新，对抗性审查 B1）** | UC-004 | design §4.2.1 |
| BR-012-no-stripe-bucket | 令牌桶不分段 | 令牌桶持「全局 QPS 上限」不变量，分段会放大 N 倍放行或误杀；保留单 AtomicLong + 自旋。分段仅用于可交换求和量 | UC-004 | design §4.2 |

#### 2.2 计算规则
| 规则 ID | 规则名称 | 计算公式/算法 | 适用用例 | 来源 |
|---------|---------|--------------|---------|------|
| BR-011-lazy-refill | 惰性时间推导补令牌 | `Tokens_add = (Time_now − Time_last) × ratePerMs`；`Tokens_new = min(capacity, Tokens_current + Tokens_add)`；成功扣 1 后 CAS | UC-004 | design §4.2.2 |
| BR-013-float-zero-fix | 浮点抹零对策 | 仅当实际生成 ≥1 个完整令牌时才推进 `Time_last`；否则保持 `Time_last`、仅回写令牌数，让时间差累积跨过整数门槛 | UC-004 | design §4.2.3 |

**详细规则说明：**

##### BR-011-lazy-refill
* **类型：** 计算（Computation）
* **描述：** 废弃后台漏桶；在请求到来瞬间按时间差推导应补充令牌。
* **输入参数：** `Time_last`、`Tokens_current`、`ratePerMs`、`capacity`、`Time_now`
* **计算公式：** `Tokens_new = min(capacity, Tokens_current + (Time_now − Time_last) × ratePerMs)`；`Tokens_new ≥ 1` 则扣 1。
* **输出：** 更新后的 `bucketState`（或阻断）。
* **边界条件：** 见 BR-013（低 QPS 抹零）。
* **关联用例：** UC-004
* **伪代码（示意）：**
```java
// 严禁复制类名；实际以设计算法实现
for (;;) {
    long cur = state.bucketState.get();
    long tLast = cur >>> 22, tok = cur & ((1<<22)-1);
    long add = (now - tLast) * ratePerMs;
    long nTok = Math.min(capacity, tok + add);
    if (nTok < 1) { /* BR-013: 不推进 tLast */ return BLOCK_RATE_LIMITER; }
    long next = (now << 22) | (nTok - 1);
    if (state.bucketState.compareAndSet(cur, next)) return OK;
}
```

##### BR-013-float-zero-fix
* **类型：** 计算（Computation）
* **描述：** 解决「QPS 极低（如 1/s=0.001/ms）下 Δt×rate 强转 long 抹零、令牌永不生成」。
* **触发条件：** `Δt × ratePerMs < 1`（不足以生成完整令牌）。
* **约束内容：** 保持 `Time_last` 不变，仅回写扣减后令牌数，让时间差持续累积直至跨过整数门槛。
* **违反后果：** 低 QPS 资源永远限流。

### 3. 约束条件
* **CAS 颠簸：** 单 AtomicLong 在 >50k QPS 下 Cache Line 颠簸；缓解：@Contended 填充（非分段）。影响 UC-004。

### 4. 成功标准
* 全局 QPS 上限不被突破（压测：设置 rate=1000/ms，持续放行数 ≤ 1000/ms）。
* 低 QPS（1/s）资源令牌能正常生成（BR-013 验证用例）。

### 5. 规则依赖
```
BR-006-monotonic-nanotime → BR-011-lazy-refill
BR-011-lazy-refill → BR-013-float-zero-fix
BR-012-no-stripe-bucket ⊥ (并发分段) BR-030-segmented-concurrency
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |
