## 用例：RCU 规则热更新（Hot Reload）

### 1. 头部与元数据
* **用例 ID：** UC-008
* **用例名称：** 动态规则与热更新（RCU 原子指针替换）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** 后台配置线程（调用编程式 API；配置中心监听器在 v1 范围外）
* **目标：** 规则动态变更时只换配置、状态跨版本稳定，从根上消除在途请求错乱（计数漂移、并发变负、僵尸限流）。
* **层级：** User-Goal

### 3. 上下文与触发器
* **触发器：** 运维/程序调用 `CONFIGS.set(resourceId, newConfig)`（或将来配置中心事件，v1 排除）。
* **前置条件：** 资源已注册。
* **后置条件：** `CONFIGS[resourceId]` 指向新不可变配置（version+1）；`STATES[resourceId]` 原地不动；在途请求 release 经 token.version 感知换代。

---

### 4. 场景与流程

#### 4.1 主成功场景
1. **配置线程：** 监听/接收新规则（v1 为编程式调用）。
2. **配置线程：** `new` 一个 `ResourceConfig`（仅纯参数，`version = old.version + 1`）。
3. **配置线程：** `CONFIGS.set(resourceId, newConfig)` 一次性替换。（Use BR-050）
4. **系统：** `STATES[resourceId]` 原地不动。（Use BR-051）
5. **在途请求：** release 从 token 解出 `version` 与当前 `CONFIGS.version` 比对（Use BR-052）。
6. **系统：** 旧 `ResourceConfig` 失去引用，下次 GC 平滑回收；无后台调度挂载，无内存泄漏。

#### 4.2 备选流程
* **6a. 参数语义变化（如 capacity 调大）：** 令牌桶每次 acquire 读 `config.capacity` 做 min 截断，新容量下次 acquire 即生效，无需迁移 state。

#### 非功能性需求
* **原子性：** `CONFIGS` 用 `volatile ResourceConfig[]`（整体引用替换）或 `AtomicReferenceArray`。
* **正确性：** 在途 release 的并发回滚始终作用在稳定 state 上，天然正确。

---

### 5. 其他要求
* **关键业务规则：** BR-050-rcu-config-swap、BR-051-state-stable、BR-052-version-check（见 `rules.md`）
* **范围约束：** v1 不含 Nacos/Apollo/ETCD 监听器（排除项），仅交付 RCU 机制 + 编程式 API。
