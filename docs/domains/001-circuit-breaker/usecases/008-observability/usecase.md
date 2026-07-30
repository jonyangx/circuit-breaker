## 用例：指标采集与 Prometheus 导出（Observability）

### 1. 头部与元数据
* **用例 ID：** UC-010
* **用例名称：** 轻量监控与 Prometheus exporter（v1 完整 exporter）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** SRE/运维；Prometheus scraper（外部系统）
* **目标：** 因丢弃 `LeapArray`/`MetricBucket`，无法提供 Sentinel 式秒级精确面板；采用轻量单调计数 + EWMA Gauge + scraper 算差值。
* **层级：** User-Goal

### 3. 上下文与触发器
* **触发器：** release 末尾异步递增 LongAdder；Prometheus scraper 周期性 scrape exporter 端点。
* **前置条件：** STATES 中 passCount/blockCount LongAdder 就位。
* **后置条件：** exporter 暴露 counter（pass/block）与 gauge（EWMA ppm）。

---

### 4. 场景与流程

#### 4.1 主成功场景（计数）
1. **Engine（release 末尾）：** 异步 `passCount.increment()` 或 `blockCount.increment()`。（Use BR-070, BR-071）

#### 4.2 主成功场景（导出）
1. **exporter：** scraper 请求到来，读 `STATES[id]` 的 `passCount.sum()`/`blockCount.sum()`（counter，单调）。
2. **exporter：** 读 ewmaState 解出 `ppm`，暴露 `ewmaErrorRate = ppm / 1e6`（gauge）。（Use BR-072）
3. **scraper：** 自身保存 `lastValue`，与当前 `sum()` 相减得增量（契合 counter 单调语义）。

#### 4.3 异常流程
* **3a. 误用 `LongAdder.reset()`：** 与并发 `add()` 存在竞态，`sum()` 到 `reset()` 之间增量会丢——禁止调用。（Use BR-071）

#### 非功能性需求
* **隔离：** 计数不在核心自旋逻辑内，仅 release 末尾异步递增；exporter 读取不阻塞热路径。

---

### 5. 其他要求
* **关键业务规则：** BR-070-monotonic-counter、BR-071-no-reset、BR-072-ewma-gauge（见 `rules.md`）
* **模块定位：** v1 提供完整 Prometheus exporter 集成（用户选大范围）。
