# BRD Analysis Report: Circuit Breaker（纳秒级无锁流量治理组件）

> 特性目录：`001-circuit-breaker` · 需求来源：`docs/brd/design.md` · 需求模式：`new-domain`
> 技术栈锁定：Java 21+ / Gradle (Kotlin DSL) / JMH · groupId `dev.circuitbreaker`（见 `memory/constitution.md`）

## Executive Summary

本特性是一个**嵌入式流量治理库（embedded SDK）**，对标 Alibaba Sentinel / Hystrix 的核心能力（熔断、限流、并发控制、系统过载保护），但以**极致性能为第一性原理**：通过状态压缩（bit-packing）、惰性计算（lazy evaluation）、时间衰减 EWMA、扁平化执行（flat execution）、配置/状态分离，将单次治理调用的额外开销压到**纳秒级**，热路径**零堆分配、无锁（单次 AtomicLong CAS）、无治理侧后台定时线程**。

业务用户为**集成该库的中间件/业务开发工程师**；价值在于让网关、Service Mesh Sidecar、RPC 框架、连接池等高并发/资源受限场景，在不引入 GC 压力与大依赖的前提下获得流量治理能力。

v1 范围（经需求拷问确认）：四类治理能力全纳入 + 独立 reactive-adapter 模块 + 完整 Prometheus exporter；**集群限流、热点参数限流、配置中心监听器**排除在 v1 之外（仅交付 RCU 机制 + 编程式热更新 API）。

## Domain Model

### Core Domains

| 分类 | 域 | 说明 |
|------|----|------|
| **核心域（Core）** | 流量治理引擎（Traffic Governance Engine） | 项目存在的理由：纳秒级、零分配、无锁的 acquire/release 决策。包含令牌桶、EWMA 熔断、并发控制、系统过载的判定与状态机。 |
| **支撑域（Supporting）** | 资源与生命周期（Resource & Lifecycle） | 资源注册、Config/State 分离、64 位 Token 编解码——为核心域提供寻址与生命周期载体。 |
| **支撑域（Supporting）** | 热更新（Hot Reload） | RCU 配置热换，保障核心域在规则动态变更下的在途请求正确性。 |
| **通用域（Generic）** | 响应式适配（Reactive Adapter） | Reactor/WebFlux 集成层，复用通用响应式模式。 |
| **通用域（Generic）** | 可观测性（Observability） | Prometheus 指标导出，复用通用监控语义。 |

### Bounded Contexts

1. **Resource & Lifecycle Context** — 整数 `resourceId` 数组寻址、`CONFIGS[]`/`STATES[]` 双槽、64 位 Token
2. **Rate Limiting Context** — 惰性令牌桶（单 AtomicLong，不分段）
3. **Circuit Breaking Context** — 时间衰减 EWMA + 三态状态机 + 代际
4. **Concurrency Control Context** — 分段近似并发（可交换求和）
5. **System Overload Context** — 分级概率丢弃 + 迟滞
6. **Hot Reload Context** — RCU 原子指针替换
7. **Reactive Adapter Context** — 跨线程 release 正确性
8. **Observability Context** — 单调计数器 + EWMA Gauge

**上下文映射**：Resource & Lifecycle 为**共享内核（Shared Kernel）**——其 Token 位布局与 Config/State 契约被其余所有上下文消费；其余治理上下文作为**客户/供应商（Customer/Supplier）**经 bitmask 被 FlatExecutionEngine 调度；Observability 以**防腐层（ACL）**只读消费 STATES。

### Entities and Relationships

```
ResourceManager ──register(name,policy)──▶ ResourceConfig(immutable, versioned)
                                                │  RCU 热换
                                                ▼
   CONFIGS[resourceId] (volatile array) ◀──── 原子指针替换
   STATES[resourceId] (final array, 长生命周期, 永不随规则替换)
        │
        ├── bucketState   AtomicLong  ── 令牌桶
        ├── breakerState  AtomicLong  ── 熔断状态机(含 generation)
        ├── ewmaState     AtomicLong  ── EWMA(含 generation/count/ppm/lastUpdateMs)
        ├── concurrency[] AtomicInteger(分段)
        └── passCount/blockCount LongAdder(只增)

FlatExecutionEngine.tryAcquire(resourceId) ──▶ long Token  ──▶ FlatExecutionEngine.release(resourceId, token, success)
        │ (bitmask 分派 0x01/0x02/0x04)                       │ (token 解码: version/bucketIdx/mask/time)
        ▼                                                     ▼
   [系统过载前置短路]                                  [EWMA 上报 / 并发回滚 / 熔断迁移]
```

**聚合根**：`ResourceState`（聚合 `bucketState`/`breakerState`/`ewmaState`/`concurrency[]`/观测计数，跨版本稳定）。
**实体**：`ResourceConfig`（不可变值对象，带 version）。
**值对象**：`Token`（64 位 long）、`BlockCode`（负数常量）、`Policy`（构建器产物）。

## Business Use Cases

### Primary Use Cases
1. **UC-001 注册资源与治理策略**：开发者注册资源，获得整数 `resourceId` 并配置能力掩码（参与：开发者、ResourceManager）
2. **UC-002 获取治理令牌 acquire**：业务调用前请求治理决策，引擎经 bitmask 分派返回 `long token` 或负阻断码
3. **UC-003 释放并上报调用结果 release**：业务调用后释放 token，触发 EWMA 上报 / 并发回滚 / 熔断状态迁移
4. **UC-004 令牌桶限流判定**（能力 0x02）
5. **UC-005 熔断判定与三态迁移**（能力 0x01）
6. **UC-006 分段并发控制判定**（能力 0x04）
7. **UC-007 系统过载分级丢弃**（前置短路）

### Secondary Use Cases
1. **UC-008 规则热更新（RCU）**：热换 ResourceConfig，STATES 原地不动
2. **UC-009 响应式流量治理**：Reactor/WebFlux 跨线程 acquire/release
3. **UC-010 指标采集与 Prometheus 导出**

> 用例细节见 `usecases/[编号]-[subdomain]/usecase.md`；业务规则见同目录 `rules.md`。

## Business Rules

### Functional Rules（摘要，详见各子领域 rules.md）
1. BR-002 config-state-separation：配置可热换 / 状态跨版本稳定（约束）
2. BR-003 token-encoding：64 位 token 位布局 `[sign:1][time:41][version:6][bucketIdx:4][mask:12]`（约束）
3. BR-012 no-stripe-bucket：令牌桶与熔断状态机不分段，保留全局不变量（约束）
4. BR-020 ewma-time-decay：`α = 1 - exp(-Δt/τ)`，时间衰减（计算）
5. BR-024 generation-aba：代际对齐消除 ABA 与陈旧错误率（推断）
6. BR-040 graded-shed-permille：分级概率丢弃 + 迟滞（动作触发）

### Non-functional Rules
1. **性能（不可逾越回归红线）**：`tryAcquire` 单线程 P50 < 100 ns；`release` P50 < 50 ns；热路径 0 字节堆分配（JMH `-gc`）
2. **并发正确性**：所有状态更新单次 AtomicLong CAS；release 不依赖执行线程
3. **资源**：治理侧无后台定时线程；观测/探针不入请求关键路径

## Success Criteria

- **可量化（功能）**：
  - 64 位 token 正确携带 version+bucketIdx，跨线程 release 并发计数零漂移（断言：concurrency 段求和归零）
  - 熔断三态迁移闭环：CLOSED↔OPEN↔HALF_OPEN 每条迁移 `generation+1`，HALF_OPEN→CLOSED 后旧代 EWMA 重播种不被二次跳闸
- **可量化（非功能）**：
  - JMH：tryAcquire P50 < 100 ns，release P50 < 50 ns（单线程基准配置）
  - JMH `-gc`：acquire/release 零字节分配（`·gc.count` 增量为 0）
  - 覆盖率：行 ≥ 80% / 分支 ≥ 70% / 方法 ≥ 85%
  - 热路径禁止出现 `Math.exp`、对象 `new`、`synchronized`（静态分析/代码评审门控）

## Assumptions and Constraints

- **假设**：单次调用真实 RT < token `time` 字段可表时长（41 位≈69.7 年），故 RT 模减法正确（design §3.2.1）。
- **约束（v1 排除）**：集群限流（需外置 Redis/Token Server，打破无锁）、热点参数限流（需 >12 位掩码）、配置中心监听器（Nacos/Apollo/ETCD）——v1 仅 RCU + 编程式 API。
- **约束**：仅支持单机极速；精准秒级集群 QPS 同步不在目标内。
- **时钟**：统一 `System.nanoTime()/1_000_000` 相对单调时间戳（启动归零 `START`），禁止 `currentTimeMillis()` 做治理判定。

## Appendices

### Glossary（统一语言 Ubiquitous Language）
- **Resource（资源）**：被治理的业务调用目标，全局唯一整数 `resourceId` 标识
- **Token（令牌）**：贯穿调用始末的 64 位 long，携带时间/版本/桶索引/掩码
- **Config / State（配置 / 状态）**：可热换的纯参数 / 长生命周期运行时状态
- **Generation（代际）**：熔断状态每迁移一次 +1 的循环标签，用于 EWMA 惰性作废
- **BlockCode（阻断码）**：全负数常量：-1 系统过载 / -2 熔断 / -3 限流 / -4 并发
- **EWMA（指数加权移动平均）**：错误率统计，时间衰减 α
- **Token Bucket（令牌桶）**：惰性补令牌的 QPS 限流器
- **Flat Execution（扁平化执行）**：bitmask 分派 + 数组寻址，废除责任链/多态
- **RCU（Read-Copy-Update）**：配置原子指针替换的热更新机制
- **SHED_PERMILLE（丢弃千分比）**：系统过载分级概率丢弃参数

### Stakeholders
详见 `artifacts/stakeholders.md`。

### 文档索引（可追溯性）
- 领域工件：`artifacts/data-model.md`、`artifacts/stakeholders.md`
- 用例/规则：`usecases/001-resource-lifecycle/` … `usecases/008-observability/`
- ease-spec 产物：`.eases/001-circuit-breaker/flow-1-analyze/{spec,plan,tasks}.md`
