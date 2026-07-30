## 业务规则：指标采集与 Prometheus 导出（Observability）

* **子领域：** 可观测性（Observability）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 业务规则目录

#### 2.1 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-070-monotonic-counter | 计数器只增不清 | `STATES` 中 `passCount`/`blockCount` LongAdder 只增不清，仅 release 末尾异步递增，不在核心自旋内 | UC-010 | design §7 |
| BR-071-no-reset | 禁止 reset | scraper 端算差值（sum − lastValue），**禁止 `LongAdder.reset()`**（与并发 add() 竞态会丢增量） | UC-010 | design §7 |

#### 2.2 计算规则
| 规则 ID | 规则名称 | 计算公式/算法 | 适用用例 | 来源 |
|---------|---------|--------------|---------|------|
| BR-072-ewma-gauge | EWMA Gauge 暴露 | 解 ewmaState 的 ppm，暴露 `ewmaErrorRate = ppm / 1e6` 为 Gauge，反映实时健康度 | UC-010 | design §7 |

**详细规则说明：**

##### BR-071-no-reset
* **类型：** 约束（Constraint）
* **描述：** counter 语义要求单调；`reset()` 与并发 `add()` 之间存在丢增量窗口。
* **触发条件：** 任何 scrape/重置逻辑。
* **约束内容：** exporter 仅读 `sum()`；增量由 scraper 端 `sum − lastValue` 计算。
* **违反后果：** scrape 间隔内部分 pass/block 增量丢失，监控失真。
* **关联用例：** UC-010

##### BR-072-ewma-gauge
* **类型：** 计算（Computation）
* **输入：** `ewmaState` 解出的 ppm（定点整数）
* **计算公式：** `gauge = ppm / 1_000_000.0`
* **输出：** 0.0–1.0 的错误率 Gauge。

### 3. 约束条件
* **性能让位：** 放弃 Sentinel 式秒级精确面板（需 LeapArray+MetricBucket）以换取热路径零分配/纳秒开销；以轻量单调计数 + EWMA Gauge 替代。

### 4. 成功标准
* pass/block counter 单调（连续 scrape 差值非负）。
* EWMA Gauge 随错误率变化收敛（与 BR-020 联动验证）。
* exporter 读取不阻塞 acquire/release 热路径（JMH 验证无尾延迟劣化）。

### 5. 规则依赖
```
BR-070-monotonic-counter → BR-071-no-reset
BR-020-ewma-time-decay → BR-072-ewma-gauge(ppm 来源)
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |
