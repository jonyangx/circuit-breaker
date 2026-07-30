## 用例：系统过载分级丢弃（Graded Load Shedding）

### 1. 头部与元数据
* **用例 ID：** UC-007
* **用例名称：** 系统级自适应过载保护（分级概率丢弃 + 迟滞）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** FlatExecutionEngine（tryAcquire 前置短路）
* **目标：** 替代 Sentinel SystemRule 的后台采集依赖；拦截链上贯彻扁平化，用分级概率丢弃 + 迟滞避免放行震荡。
* **层级：** Sub-function

### 3. 上下文与触发器
* **触发器：** UC-002 主流程第 1 步，读 `volatile SHED_PERMILLE`。
* **前置条件：** 后台低频探针（每 1s 采集 CPU）维护 `SHED_PERMILLE`。
* **后置条件：** 命中概率丢弃返回 `BLOCK_SYSTEM_OVERLOAD(-1)`；否则进入资源级校验。

---

### 4. 场景与流程

#### 4.1 主成功场景
1. **Engine：** 读 `shed = SHED_PERMILLE`（volatile 单次读）。（Use BR-040）
2. **Engine：** 若 `shed > 0` 且 `ThreadLocalRandom.nextInt(1000) < shed`，返回 `-1`（O(1) 概率拦截，不进入资源策略）。
3. **Engine：** 否则继续 UC-002 资源级校验。

#### 4.2 后台探针（非热路径）
1. **探针线程：** 每 1s 采集 CPU；按分级阈值更新 `SHED_PERMILLE`（0/200/500/800）。（Use BR-041, BR-042）
2. **迟滞：** 进入某档阈值高于退出该档阈值（进 500 需 CPU>85% 持续 3s，退回 200 需 CPU<75%），避免临界抖动。

#### 非功能性需求
* **隔离：** 探针是唯一允许的治理相关后台线程，但**不在请求关键路径**（不违反"治理侧无定时器"，仅观测/系统探针例外，见 constitution 不变量 4）。

---

### 5. 其他要求
* **关键业务规则：** BR-040-graded-shed-permille、BR-041-hysteresis、BR-042-probe-off-path（见 `rules.md`）
