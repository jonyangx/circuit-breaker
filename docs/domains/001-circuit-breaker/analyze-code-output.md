# Code Analysis Report: Circuit Breaker（代码逆向 + 漂移检测）

> 来源：`src/main/java/dev/circuitbreaker/**`（906 行，9 个包）。语言：Java 21。
> 分析日期：2026-07-30 · 单一 Domain（理由见下）· 与 BRD 模型（`analyze-brd-output.md`）交叉验证。
> 证据驱动：每条结论引用 `文件:符号`。

## Executive Summary

代码是一个**单一内聚的流量治理库**（一个限界上下文），由**共享内核 + 四类治理能力 + 三类外设（reactive/observability/benchmarks）**构成。代码反向推导出的领域模型、用例、业务规则与 BRD 文档（`analyze-brd-output.md` + 8 子领域）**整体一致**；但近 4 个修复提交引入了 BRD 文档**未记录**的行为（HALF_OPEN 自愈、配置校验、令牌桶容量上限、异常统一）——见 §漂移检测。

## Source Code Overview（包 → 模块）

```
src/main/java/dev/circuitbreaker/
├── core/                     共享内核（9 类）
│   ├── TokenCodec            64 位 token 编解码
│   ├── ClockSource           nanoTime 相对单调时钟
│   ├── BlockCode             阻断码 -1/-2/-3/-4
│   ├── ResourceConfig        不可变配置（实体）
│   ├── ResourceState         聚合根（运行时状态）
│   ├── ResourceManager       注册 + 数组寻址
│   ├── FlatExecutionEngine   公共入口 + bitmask 分派
│   ├── PolicyBuilder         策略构建 + 校验
│   ├── PolicySpec            离线跨参数 SLA 校验（S1–S5）
│   ├── GovernanceException   块码→类型化异常（base + 4 子类）
│   ├── breaker/              EwmaAlpha + EwmaCircuitBreaker（熔断）
│   ├── ratelimit/            LazyTokenBucket（限流）
│   ├── concurrency/          SegmentedConcurrency（并发）
│   ├── system/               SystemOverload（系统过载）
│   └── reload/               ConfigSwapper（RCU 热更新）
├── reactive/                 CircuitBreakerOperator（Reactor 适配）
└── observability/            CircuitBreakerCollector（Prometheus 导出）
```

## Domain Model（代码逆向）

### 单一 Domain 判定（偏离"多 Domain"准则的说明）
代码包 `core/{breaker,ratelimit,concurrency,system}` 是**同一流量治理组件的不同能力**，共享同一聚合根 `ResourceState`、同一 `Token`、同一 `CONFIGS/STATES` 双槽——是一个限界上下文，非独立业务域。故识别为**单一 Domain**（与 BRD `001-circuit-breaker` 一致）。多 Domain 准则适用于多业务模块存量系统，不适用于单一内聚库。

### 核心域 / 支撑域 / 通用域
- **核心域**：流量治理引擎（`FlatExecutionEngine` + 四能力）
- **支撑域**：资源与生命周期（`ResourceManager`/`ResourceConfig`/`ResourceState`/`TokenCodec`）、热更新（`ConfigSwapper`）
- **通用域**：reactive 适配、observability 导出

### 聚合根 / 实体 / 值对象
- 聚合根：`ResourceState`（`ResourceState.java` — bucketState/breakerState/ewmaState AtomicLong + concurrency[16] + pass/block LongAdder）
- 实体：`ResourceConfig`（不可变，含 version）
- 值对象：`Token`（long）、`BlockCode`（负 long 常量）、`GovernanceException` 层级

## Business Use Cases（代码 ↔ BRD UC 映射）

| 代码入口 | BRD 用例 | 一致性 |
|---|---|---|
| `FlatExecutionEngine.tryAcquire/release` | UC-002/003 获取/释放治理令牌 | ✅ |
| `ResourceManager.register` | UC-001 注册资源 | ✅ |
| `LazyTokenBucket.tryAcquire` | UC-004 令牌桶限流 | ✅（+漂移 D3） |
| `EwmaCircuitBreaker.tryAcquire/release` | UC-005 EWMA 熔断三态 | ✅（+漂移 D1） |
| `SegmentedConcurrency.tryAcquire/release` | UC-006 分段并发 | ✅ |
| `SystemOverload.maybeShed` | UC-007 系统过载丢弃 | ✅ |
| `ConfigSwapper.swap` | UC-008 RCU 热更新 | ✅ |
| `CircuitBreakerOperator.wrap` | UC-009 响应式治理 | ✅（+漂移 D4） |
| `CircuitBreakerCollector.collect` | UC-010 Prometheus 导出 | ✅ |
| `PolicyBuilder.sla` / `PolicySpec.check` | UC-011/012 SLA 推导 + 构造期校验 | ✅（增量 D6） |

## Business Rules（代码 ↔ BRD BR 映射）
- BR-002 配置/状态分离 ↔ `ResourceManager.CONFIGS`(AtomicReferenceArray)/`STATES`(final[]) ✅
- BR-003 token 位布局 ↔ `TokenCodec` 常量 ✅
- BR-005 bitmask 分派 ↔ `FlatExecutionEngine.tryAcquire` ✅
- BR-011/013 惰性令牌桶/抹零 ↔ `LazyTokenBucket.tryAcquire` ✅（+漂移 D3 容量上限）
- BR-020/024/025 EWMA/代际/状态机 ↔ `EwmaCircuitBreaker` ✅（+漂移 D1 HALF_OPEN 自愈）
- BR-031/032 TLR probe/桶回滚 ↔ `SegmentedConcurrency` ✅
- BR-040/041 分级/迟滞 ↔ `SystemOverload.onCpuSample` ✅
- BR-050/052 RCU/版本校验 ↔ `ConfigSwapper`/`release` ✅
- BR-060 线程无关 release ↔ `FlatExecutionEngine.release`（token 解码）✅
- BR-080/081/082 SLA 推导 / S1–S5 不变量 / opt-in 校验 ↔ `PolicySpec` + `PolicyBuilder.sla` ✅（增量 D6）

## 漂移检测（Drift — 代码已演进，BRD 文档待回填）

| # | 代码行为（提交） | BRD 文档现状 | 建议 |
|---|---|---|---|
| **D1** | HALF_OPEN 探路超时自愈：`EwmaCircuitBreaker.tryAcquire` 把 endTime 当探路截止，过期惰性回退 OPEN 重选探路（`abcfe30`） | `usecases/003-circuit-breaking` 与 BR-025 未提及自愈 | 回填 BR-025 / UC-005 异常流程：丢失探路自愈 |
| **D2** | PolicyBuilder 入参校验：`build()` 拒绝 errThreshold∉(0,1]、τ/minCalls/qps≤0、qps>4.19M（`abcfe30`） | 无对应 BR | 新增 BR：配置校验约束（resource-lifecycle rules） |
| **D3** | 令牌桶 22 位容量上限：`LazyTokenBucket` nTok cap TOKEN_MASK + PolicyBuilder 拒绝 qps>4,194,303（`932aaab`） | BR-010 未提字段上限 | 回填 BR-010：capacity/qps ≤ 2²²-1 |
| **D4** | GovernanceException 统一映射：reactive `CircuitBreakerOperator` 经 `GovernanceException.forToken` 抛类型化异常，删除 `CircuitBreakerBlockedException`（`1343fd5`/`679faaf`） | UC-009 用 `CircuitBreakerBlockedException`，UC-002 用裸 switch | 回填 UC-002/UC-009：块码→GovernanceException 子类 |
| D5 | `ResourceManager.register` 注册时 `LazyTokenBucket.seed` 预充满桶（实现细节） | 未提 | 可选补充（初始突发可用） |
| **D6** | 工作树变更：`PolicySpec`（S1–S5 离线校验）+ `PolicyBuilder.sla()` opt-in；`LazyTokenBucket` 改 ms 粒度 `dtMs×qps/1000`；`register` 去 name；token C2 扩位 version 6→10 / time 41→37 | PolicySpec 全新无对应 UC/BR；data-model / 001-usecase 已部分同步 | ✅ 已登记：新增 `009-policy-validation`（UC-011/012, BR-080~082）；BR-003 位宽修 37/10；data-model `ratePerMs`→`qps` |

> ✅ **D1–D4 已回填（2026-07-30）**：BR-025（HALF_OPEN 自愈）、BR-007-config-validation（新增）、BR-010（22 位上限）、BR-004（GovernanceException 映射）——见对应 `usecases/*/rules.md`。BRD 历史文档现已与代码一致。

一致项（无漂移）：SEG=16（BRD 谓"8 或 16"）、RCU `publishConfig`（BR-050）、代际机制（BR-024）、token 自描述（BR-060）、分级迟滞（BR-041）。

## Technical Insights
- 热路径零分配：`tryAcquire/release` 仅原始类型局部量；JMH 实测 `gc.count≈0`（BENCHMARKS.md）。
- 无锁：状态更新单 `AtomicLong` CAS；`@Contended` 未实现（design 提及，未落地——非漂移，待办）。
- ArchUnit 守护：`HotPathGuardTest` 禁热路径 `Math.exp`/`synchronized`。
- 45 测试全绿；覆盖率 LINE 93.4% / BRANCH 84.3% / METHOD 98.6%。

## Recommendations（建议执行）
1. **回填 D1–D4 到 BRD 文档**（usecase 002/003、rules 001/002/003）以恢复 code↔docs 一致。
2. `@Contended` 填充（design §6.1 提及未落地）作为后续优化。
3. 版本 10 位回绕（A5）、lastUpdateMs 20 位（A4）记为已知局限。

## Appendices
- 术语表：见 `analyze-brd-output.md` Glossary。
- 代码引用：本文已内联 `文件:符号`。
