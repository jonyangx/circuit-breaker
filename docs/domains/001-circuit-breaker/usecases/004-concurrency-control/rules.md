## 业务规则：分段并发控制（Segmented Concurrency Control）

* **子领域：** 并发控制（Concurrency Control）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 业务规则目录

#### 2.1 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-030-segmented-concurrency | 分段近似并发 | 并发数用 `AtomicInteger concurrency[SEG]`（SEG=8 或 16）近似统计，acquire 对路由段 +1、release 对同段 -1；sum 近似，允许轻微过冲换无锁 | UC-006 | design §4.4 |
| BR-031-tlr-probe-route | TLR probe 路由 | 路由用 `ThreadLocalRandom` probe，**非 threadId**（Java 21+ 虚拟线程 id 唯一且短命，threadId&(SEG-1) 无热点局部性、数量无界，stripe 失效） | UC-006 | design §4.4 |
| BR-032-bucket-idx-rollback | 桶索引回滚同段 | acquire 把 `bucketIdx` 写入 token；release 从 token 解出 `bucketIdx` 回到同一段 -1，不依赖执行线程 | UC-006 | design §3.2 / §4.4 |

**详细规则说明：**

##### BR-031-tlr-probe-route
* **类型：** 约束（Constraint）
* **描述：** 路由段选择改用 LongAdder 同款 probe，而非 `threadId`。
* **触发条件：** 每次 acquire。
* **约束内容：** `bucketIdx = ThreadLocalRandom.current().nextInt() & (SEG−1)`（或 probe 字段）。
* **违反后果：** 虚拟线程场景下 stripe 失效，热点集中、并发统计失真。

##### BR-032-bucket-idx-rollback
* **类型：** 约束（Constraint）
* **描述：** bucketIdx 内嵌 token，使 release 回到 acquire 时的同一段。
* **约束内容：** release 解码 `decodeBucket(token)` 后对 `concurrency[idx]` decrementAndGet。
* **违反后果：** Reactor/Netty 跨线程 release 扣错桶，并发计数漂移。

### 3. 约束条件
* **可分段 vs 不可分段：** 并发计数（可交换求和）可分段；令牌桶/熔断状态机（持全局不变量）不可分段（与 BR-012 一致）。

### 4. 成功标准
* 跨线程 release 后 `sum(concurrency)` 归零（断言）。
* 并发上限近似生效：并发 > limit 时大量请求被 `-4` 阻断。

### 5. 规则依赖
```
BR-003-token-encoding(bucketIdx) → BR-032-bucket-idx-rollback
BR-031-tlr-probe-route → BR-030-segmented-concurrency
BR-030-segmented-concurrency ∥ BR-012-no-stripe-bucket（分段边界区分）
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |
