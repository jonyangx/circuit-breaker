# 系统架构设计：Circuit Breaker（纳秒级无锁流量治理组件）

**特性分支**：`001-circuit-breaker`
**创建时间**：2026-07-30
**来源**：用例文档 `docs/domains/001-circuit-breaker/usecases/`（8 子领域，UC-001..010 / BR-001..072）、`memory/constitution.md`、`docs/brd/design.md`
**状态**：Draft
**备注**：遵循 `memory/constitution.md`，任何 MUST 原则冲突将导致设计无效。本库为 greenfield，无 `docs/system/`；本设计即建立初始系统知识。

## 0. 设计元数据（Design Metadata）

- **场景类型**: `generic`（嵌入式高性能库，无标准模板匹配；最接近"中间件/SDK"但不在内置场景库）
- **架构模板**: `templates/design/scenario-templates/generic-template.md`
- **识别置信度**: 低（重度定制）
- **定制化程度**: 重度定制（调整 ≥ 30%）
- **定制化原因**: 本组件为进程内嵌入式流量治理库，非请求/响应式 Web 服务；核心约束是纳秒级/零分配/无锁，远超通用模板假设。
- **需求模式**: `new-domain`（无 delta-analysis.md，§0.5 省略）

## 1. 概览与技术栈对齐

- **设计目标**（源自 spec.md / 用例）：纳秒级 acquire/release、零堆分配、无锁（单 AtomicLong CAS）、无治理侧后台定时线程；覆盖熔断/限流/并发/系统过载四能力 + reactive 适配 + Prometheus 导出。
- **技术栈**（与 constitution §Tech & Process Constraints 对齐）：Java 21+、Gradle（Kotlin DSL）、JMH、JUnit5+AssertJ+JaCoCo、groupId `dev.circuitbreaker`。
- **宪章映射**：Library-First（自包含库，CLI 豁免）、Test-First（TDD NON-NEGOTIABLE）、Observability（US7 exporter）、6 条项目不变量（性能/零分配/无锁/无定时线程/配置-状态分离/自描述 token）。
- **集成点**：被业务/中间件经 Java API 内嵌；reactive 模块经 Reactor 操作符接入 WebFlux 链；observability 模块经 Prometheus collector 被 scraper 抓取。

## 1.5 场景特定设计（重度定制）

**架构模式**：
- 模板推荐（generic）：分层服务 + 数据访问。
- 当前选择：**扁平化执行引擎 + bit-packed 原始类型状态**（无 OOP 责任链、无多态、无对象承载生命周期）。
- 选择理由：不变量1/2/3（纳秒级/零分配/无锁）要求废除责任链与对象分配，generic 模板的分层抽象会引入方法栈深度与对象，违反不变量。

**核心组件**（裁剪后）：
- TokenCodec / ClockSource / ResourceConfig / ResourceState / ResourceManager / FlatExecutionEngine（共享内核）
- LazyTokenBucket / EwmaCircuitBreaker(+EwmaAlpha) / SegmentedConcurrency / SystemOverload（四能力，经 bitmask 分派）
- ConfigSwapper（RCU）/ CircuitBreakerOperator（reactive）/ CircuitBreakerCollector（observability）

**数据流**：`业务调用 → tryAcquire(resourceId) → [系统过载前置短路] → 读 CONFIGS/STATES → bitmask 分派四能力 → 打包 long token / 负阻断码 → 业务执行 → release(token, success) → 解码 token → 并发回滚/EWMA 上报/熔断迁移/计数`。

**调整清单**：
1. 废除 generic 模板的"服务层+仓储层"分层 → 扁平化原始类型数组寻址（理由：零分配/纳秒级）。
2. 废除"DTO/请求-响应对象" → 64 位 long token 作为唯一生命周期载体（理由：零分配 + reactive 线程无关）。
3. 废除"定时任务调度" → 惰性时间推导（理由：不变量4）。

## 2. 用例到技术的映射

### 2.1 用例-模块映射表

| 用例 | 用例名称 | 涉及模块 | 优先级 |
|------|----------|----------|--------|
| UC-001 | 注册资源与策略 | core/ResourceManager, ResourceConfig, ResourceState | P1 |
| UC-002 | 获取治理令牌 acquire | core/FlatExecutionEngine, TokenCodec, ClockSource | P1 |
| UC-003 | 释放并上报 release | core/FlatExecutionEngine | P1 |
| UC-004 | 令牌桶限流 | core/ratelimit/LazyTokenBucket | P1 |
| UC-005 | EWMA 熔断三态 | core/breaker/EwmaCircuitBreaker, EwmaAlpha | P1 |
| UC-006 | 分段并发控制 | core/concurrency/SegmentedConcurrency | P2 |
| UC-007 | 系统过载丢弃 | core/system/SystemOverload | P2 |
| UC-008 | RCU 热更新 | core/reload/ConfigSwapper | P3 |
| UC-009 | 响应式治理 | reactive/CircuitBreakerOperator | P3 |
| UC-010 | Prometheus 导出 | observability/CircuitBreakerCollector | P3 |

### 2.2 技术拆解说明
- 共享内核（TokenCodec/ClockSource/CONFIGS/STATES）由 UC-001/002/003 建立，被全部能力复用。
- 四能力经 `config.mask` 位与分派（UC-002），互不耦合；release 经 token 解码回滚/上报（UC-003）。
- reactive/observability 为独立模块，仅依赖 core 的公共 API 与 ResourceState 只读访问。

## 3. 组件与边界

```
circuit-breaker-core（零三方依赖，共享内核 + 四能力）
  ├─ TokenCodec          (BR-003) 64 位 encode/decode，无分支无对象
  ├─ ClockSource         (BR-006) nanoTime/1M - START 单调相对时钟
  ├─ BlockCode           (BR-004) -1/-2/-3/-4 常量
  ├─ ResourceConfig      (BR-002/050) 不可变参数 + version
  ├─ ResourceState       (聚合根 BR-002/051) bucketState/breakerState/ewmaState + concurrency[] + LongAdder
  ├─ ResourceManager     (BR-001) register → resourceId + CONFIGS[]/STATES[]
  ├─ FlatExecutionEngine (BR-005) tryAcquire/release + bitmask 分派（公共入口）
  ├─ ratelimit/LazyTokenBucket        (BR-010..013)
  ├─ breaker/EwmaCircuitBreaker       (BR-020..025) + EwmaAlpha (BR-021)
  ├─ concurrency/SegmentedConcurrency (BR-030..032)
  ├─ system/SystemOverload            (BR-040..042)
  └─ reload/ConfigSwapper             (BR-050..052)

circuit-breaker-reactive   → 依赖 reactor-core；CircuitBreakerOperator（BR-060/061）
circuit-breaker-observability → 依赖 prometheus simpleclient；CircuitBreakerCollector（BR-070..072）
circuit-breaker-benchmarks → JMH；AcquireReleaseBenchmark（SC-001/002 验证）
```

**边界契约**：core 仅暴露 `FlatExecutionEngine`、`ResourceManager`、`PolicyBuilder`、`ResourceConfig/State`（公共字段/访问器）、`BlockCode`；其余能力类包级可见，由引擎分派。reactive/observability 仅消费 core 公共 API。

## 4. 接口契约（Java 公共 API）

> 本库为嵌入式 SDK，"接口"为 Java 公共方法（非 HTTP）。详见 `api-interface-report.md`。

- `ResourceManager.register(String name, Policy policy) -> int resourceId`（UC-001）
- `FlatExecutionEngine.tryAcquire(int resourceId) -> long token`（UC-002，token<0 即阻断）
- `FlatExecutionEngine.release(int resourceId, long token, boolean success)`（UC-003）
- `PolicyBuilder.enableRateLimit(long qps)/enableCircuitBreaker(float errThreshold)/...build()`（UC-001 配置）
- `ConfigSwapper.swap(int resourceId, ResourceConfig newConfig)`（UC-008）
- reactive：`CircuitBreakerOperator` 包裹 `Mono<T>`（UC-009）
- observability：`CircuitBreakerCollector` 注册到 Prometheus registry（UC-010）

**错误语义**：阻断以负 `long` 表达（BR-004），非异常抛出（避免栈分配）；非法 resourceId/参数抛 `IllegalArgumentException`。

## 5. 关键流程伪代码（按用例）

### UC-002: acquire（系统过载前置 + bitmask 分派）
```pseudo
# Given 资源已注册
# When 业务调用 tryAcquire(resourceId)
# Then 返回 long token(>=0 放行) 或负阻断码
long tryAcquire(int rid):
    if SHED_PERMILLE>0 and rand(1000)<SHED_PERMILLE: return -1   # BR-040 前置短路
    cfg = CONFIGS[rid]; st = STATES[rid]; now = clock.nowRelMs()
    if cfg.mask & 0x01 and not breaker.tryAcquire(st,now): return -2  # BR-025
    if cfg.mask & 0x02 and not bucket.tryAcquire(st,cfg,now): return -3 # BR-011
    bidx = -1
    if cfg.mask & 0x04:
        bidx = concurrency.tryAcquire(st,cfg)                      # BR-031
        if bidx < 0: return -4
    return TokenCodec.encode(now, cfg.version, bidx, cfg.mask)     # BR-003
```

### UC-003: release（解码 + 回滚 + 上报 + 迁移）
```pseudo
# Given 有效 token(>=0)
# When release(resourceId, token, success)
void release(int rid, long token, boolean ok):
    cfg = CONFIGS[rid]; st = STATES[rid]; now = clock.nowRelMs()
    bidx = decodeBucket(token); ver = decodeVersion(token); mask = decodeMask(token)
    if mask & 0x04: concurrency.release(st, bidx)                  # BR-032 线程无关回滚
    if mask & 0x01:
        verMatch = (ver == cfg.version)                            # BR-052
        breaker.release(st, now, ok, cfg, verMatch)                # BR-020/024/025
    st.passCount.increment() if 放行 else st.blockCount.increment()# BR-070
```

### UC-005: 熔断 release 侧迁移（含代际）
```pseudo
# breaker.release: HALF_OPEN→CLOSED/OPEN；CLOSED→updateEwma 可能→OPEN
if state==HALF_OPEN: transition(ok? CLOSED: OPEN, ...)            # gen+1
elif state==CLOSED:
    gNow = gen(breakerState); updateEwma(st, now, ok?0:1_000_000) # BR-024 代际校验
    if ewmaGen==gNow and count>=minCalls and ppm>=thr: transition(OPEN,...)
```

（UC-001/004/006/007/008/009/010 伪代码见 detail-design.md）

## 6. 安全设计

- **威胁模型**：本库为进程内组件，无网络面；主要风险是**误杀（阻断合法流量）**与**计数漂移（计数变负/资源泄漏）**。
- **防护**：阻断码全负、符号位保留（BR-004）防止 token 被误判为阻断；并发段求和归零断言（SC-004）防漂移；热更新版本校验（BR-052）防在途错乱。
- **敏感数据**：不处理用户敏感数据；指标仅暴露计数/错误率聚合，无 PII。

## 7. 性能与可靠性

- **SLO/SLA**（SC-001/002，不可逾越回归红线）：tryAcquire P50<100ns、release P50<50ns、热路径 0 字节分配（JMH `-gc`）。
- **并发控制**：单 AtomicLong CAS 自旋；@Contended 填充隔离伪共享（令牌桶/breakerState）。
- **背压**：系统过载分级丢弃（UC-007）即背压机制。
- **熔断与隔离**：UC-005 本身即熔断；HALF_OPEN 探路门闩保证至多一个在途探路。

## 8. 数据一致性与事务

- **一致性**：无跨节点事务（单机）。每个 AtomicLong 状态字段单次 CAS 原子；`breakerState` 与 `ewmaState` 跨字段一致性由**代际标签**（BR-024）惰性对齐，无跨 AtomicLong 复合原子操作。
- **迁移/版本**：配置 version（token 低 6 位）做在途校验（BR-052）；位布局常量变更为破坏性版本（MAJOR）。
- **回滚**：热更新可再次 `ConfigSwapper.swap` 回退（STATES 不动，回退无状态迁移成本）。

## 9. 可观测性

- **指标**（UC-010）：pass/block counter（LongAdder 单调，scraper 算差值，BR-071）、EWMA ppm→Gauge（BR-072）。
- **日志**：热路径**禁止**日志（避免分配）；仅注册/热更新/异常路径可结构化日志。
- **告警**：EWMA Gauge 超阈值、block 率突增——由外置 Prometheus alerting 承接。

## 10. 运维与发布

- **部署**：作为依赖 jar 内嵌；多模块按需引入（core 必选，reactive/observability 可选）。
- **发布**：语义化版本（constitution VI）；破坏性位布局变更需 MAJOR + 迁移说明。
- **回滚**：依赖版本回退即可（状态全在内存，进程重启归零）。

## 11. 风险与权衡

| 风险 | 等级 | 权衡/缓解 |
|------|------|-----------|
| 超 50k QPS 单 AtomicLong CAS 颠簸 | 中 | @Contended 填充（不分段，保全局不变量，design §6.1） |
| 位布局紧凑→可读性下降 | 低 | 位宽/偏移为编译期常量 + TokenCodec 集中编解码 |
| 放弃精确秒级面板 | 中 | 取舍为性能；以单调 counter + EWMA Gauge 补偿（observability US7） |
| HALF_OPEN 探路门闩实现选择 | 低 | 两种实现（breakerState 借位 / 独立字段），detail-design 选定 |
| Reactive 线程漂移 | 低 | token 自描述（BR-060）从根消除，UC-009 集成测试覆盖 |

## 12. 宪章检查（MUST 映射）

| MUST 原则/不变量 | 满足方式 | 设计要点引用 |
|----------------|----------|-------------|
| III Test-First | tasks.md TDD 顺序；测试骨架先 fail | §2, tasks |
| I Library-First | 自包含库，CLI 豁免（constitution 记录） | §3 |
| V Observability | US7 Prometheus collector | §9 |
| 不变量1 性能 | tryAcquire/release 纳秒级，SC-001 | §7, UC-002/003 伪代码 |
| 不变量2 零分配 | long token，无对象 | §1.5, TokenCodec |
| 不变量3 无锁 | 单 AtomicLong CAS | §3, §7 |
| 不变量4 无治理定时线程 | 惰性推导；仅观测/探针非热路径 | §1.5, UC-002 |
| 不变量5 配置-状态分离 | CONFIGS/STATES 双槽 | §3, BR-002/051 |
| 不变量6 自描述 token | token 携 version+bucketIdx | §4, UC-003 |

## 附录

- 数据结构位布局见 `detail/detail-design.md` §2、`artifacts/data-model.md`。
- 时序图：acquire/release 流见 §5 伪代码（mermaid 状态机见 `docs/brd/design.md` §4.3.3）。
