## 用例：分段并发控制（Segmented Concurrency Control）

### 1. 头部与元数据
* **用例 ID：** UC-006
* **用例名称：** 分段并发控制判定（能力 0x04）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** FlatExecutionEngine（acquire 经 0x04 判定 +1；release 经 token.bucketIdx 回滚 -1）
* **目标：** 以分段 AtomicInteger 近似统计并发数，换取无锁；允许超高并发下轻微过冲。
* **层级：** Sub-function

### 3. 上下文与触发器
* **触发器：** acquire `mask & 0x04`；release `mask & 0x04`。
* **前置条件：** `concurrency[SEG]` 就位。
* **后置条件：** acquire 对路由段 +1 并把 `bucketIdx` 写入 token；release 对同段 -1。

---

### 4. 场景与流程

#### 4.1 主成功场景（acquire）
1. **并发控制：** 用 `ThreadLocalRandom` probe 路由到段 `bucketIdx = probe & (SEG−1)`。（Use BR-031）
2. **并发控制：** 若 `sum(concurrency) < concurrencyLimit`，对 `concurrency[bucketIdx]` +1；返回放行（bucketIdx 进入 token）。
3. **并发控制：** 否则返回 `BLOCK_CONCURRENCY(-4)`。

#### 4.2 主成功场景（release）
1. **并发控制：** 从 token 解出 `bucketIdx`，对 `concurrency[bucketIdx]` -1。（Use BR-032）

#### 4.3 异常流程
* **3a. 版本已变（热更新）：** release 仍按 token.bucketIdx 回滚同段（作用在稳定 STATES，永远正确）。
* **过冲：** sum 是近似值，超高并发下允许轻微超限，换取无锁（设计取舍）。

#### 非功能性需求
* **性能：** 分段降低热点；release 不依赖线程（bucketIdx 来自 token）。

---

### 5. 其他要求
* **关键业务规则：** BR-030-segmented-concurrency、BR-031-tlr-probe-route、BR-032-bucket-idx-rollback（见 `rules.md`）
