# 功能规范：Circuit Breaker（纳秒级无锁流量治理组件）

**功能分支**：`001-circuit-breaker`
**创建时间**：2026-07-30
**状态**：草稿（待 Phase 1 门控确认）
**输入**：`docs/brd/design.md`（中文设计文档）· 经需求拷问确认 v1 范围

> 需求分析权威来源：`docs/domains/001-circuit-breaker/`（用例 UC-001..UC-010、业务规则 BR-001..BR-072）。
> 本规范聚焦"是什么/为什么"，技术选型见 `memory/constitution.md` 与 `plan.md`。

## 用户场景与测试（必填）

### 用户故事 1 - 注册资源并以令牌桶限流拦截调用（优先级：P1）🎯 MVP

作为中间件/业务开发工程师，我希望注册一个资源并配置 QPS 上限，随后在业务调用前后用一次「获取令牌 → 释放」完成限流，使被保护的目标不被超过上限的流量压垮——且这次治理的额外开销在纳秒级、不分配任何堆对象。

**优先级原因**：这是组件最基础的可用切片——单独实现即可交付一个可用的限流器，构成 MVP；也是其余治理能力所依赖的「资源注册 + acquire/release + token」骨架。

**独立测试**：注册一个 rate=1000/ms 的资源，以 2000/ms 速率调用 tryAcquire，断言约一半返回阻断码 `-3`、令牌永不超额生成；JMH 断言 acquire 零字节分配、P50 < 100ns。

**验收场景**：
1. 给定已注册资源（限流能力开启），当 QPS 超过上限，则超额请求返回 `BLOCK_RATE_LIMITER(-3)`，未超额请求返回 ≥0 的 token。
2. 给定一次成功 acquire，当随后 release，则可继续发起下一次调用（令牌被正确补扣）。
3. 给定极低 QPS（1/s）资源，当空闲较久后调用，则令牌能正常生成（不被浮点抹零卡死）。

---

### 用户故事 2 - 以时间衰减 EWMA 熔断保护不稳定依赖（优先级：P1）

作为开发工程师，我希望为目标依赖启用熔断：当错误率持续超阈值时自动跳闸切断调用，一段时间后半开探测、成功则恢复——且无需后台定时线程，统计用时间衰减 EWMA。

**优先级原因**：熔断是流量治理的核心价值之一，与限流并列为 P1；独立于并发/过载即可交付。

**独立测试**：注册熔断资源（minCalls=20，errThreshold=50%，openMillis=1s），连续失败 20+ 次后断言 acquire 返回 `-2`（OPEN）；等待 openMillis 后断言放行单个探路（HALF_OPEN）；探路成功后断言恢复放行（CLOSED）且不被旧错误率二次跳闸。

**验收场景**：
1. 给定 CLOSED 且样本数 ≥ minCalls，当 EWMA 错误率 ≥ 阈值，则迁移到 OPEN，acquire 返回 `-2`。
2. 给定 OPEN 且已到 endTime，当首个 acquire 到达，则唯一线程迁移到 HALF_OPEN 并放行探路，其余仍阻断。
3. 给定 HALF_OPEN 探路，当 release 上报成功，则迁移回 CLOSED；旧代 EWMA 在下次更新被重播种（代际机制）。

---

### 用户故事 3 - 以分段并发控制限制在途调用数（优先级：P2）

作为开发工程师，我希望限制某资源的同时在途调用数，超出则阻断，保护下游连接池/线程池。

**优先级原因**：并发控制是四类能力之一，依赖 token 携带 bucketIdx；P2，可在 US1/US2 后增量交付。

**独立测试**：注册并发上限=10 的资源，并发发起 20 个 acquire（持不 release），断言约 10 个放行、其余返回 `-4`；逐一 release 后断言并发段求和归零。

**验收场景**：
1. 给定并发未达上限，当 acquire，则路由段 +1、返回携带 bucketIdx 的 token。
2. 给定并发已达上限，当 acquire，则返回 `BLOCK_CONCURRENCY(-4)`。
3. 给定跨线程 release，当从 token 解出 bucketIdx 回滚，则并发计数零漂移。

---

### 用户故事 4 - 系统过载时分级概率丢弃（优先级：P2）

作为 SRE，我希望在宿主机 CPU 过载时，组件按分级概率提前丢弃请求，避免雪崩，且过载边缘不震荡。

**优先级原因**：系统级自适应保护，独立于资源级能力；P2。

**独立测试**：模拟 CPU 探针写入 `SHED_PERMILLE=500`，断言约 50% 顶层 acquire 直接返回 `-1` 且不进入资源策略；置 0 时断言零拦截。

**验收场景**：
1. 给定 `SHED_PERMILLE > 0`，当随机命中，则返回 `BLOCK_SYSTEM_OVERLOAD(-1)`，先于资源级校验。
2. 给定 CPU 持续高位，当探针升档，则丢弃率上升；当 CPU 回落越过退档阈值（迟滞），则丢弃率下降，无来回抖动。

---

### 用户故事 5 - 运行时热更新规则（RCU）（优先级：P3）

作为运维/开发，我希望不重启即调整某资源的能力参数（如调大 QPS），且在途请求的释放计数不被错乱。

**优先级原因**：动态治理刚需，但依赖配置-状态分离骨架已就位；P3。v1 仅 RCU + 编程式 API（配置中心监听器排除）。

**独立测试**：注册 rate=1000/ms 资源，热换为 2000/ms（version+1），断言下一次 acquire 即按新容量生效；acquire 与 release 之间热换，断言并发段求和仍归零、不出现负值。

**验收场景**：
1. 给定新规则，当 `CONFIGS.set(newConfig)`，则 `STATES` 原地不动，新参数下次 acquire 生效。
2. 给定 acquire 与 release 之间发生热换，当 release 校验 token.version 不匹配，则并发照常回滚、EWMA 降权/跳过。

---

### 用户故事 6 - 响应式（Reactor/WebFlux）流量治理（优先级：P3）

作为使用 WebFlux 的开发工程师，我希望在响应式链中安全地 acquire/release，即便 Reactor 线程切换也不会计数漂移或上下文丢失。

**优先级原因**：核心库本身即 reactive-safe（token 自描述），本故事交付独立 adapter 模块（便捷操作符）；P3。

**独立测试**：在 `Mono.defer` 中 acquire、在 `doOnSuccess/doOnError` 中 release（不同 Reactor 线程），断言并发段求和归零、EWMA 正确上报。

**验收场景**：
1. 给定响应式调用，当 acquire 与 release 发生在不同线程，则 token 作为闭包捕获的 long 被正确释放，无 ThreadLocal 依赖。

---

### 用户故事 7 - Prometheus 指标导出（优先级：P3）

作为 SRE，我希望以 Prometheus 抓取 pass/block 计数与 EWMA 错误率健康度，监控治理效果。

**优先级原因**：可观测性，完整 exporter 集成；P3。计数只增不清、scraper 算差值。

**独立测试**：连续触发 pass/block 后 scrape exporter，断言 counter 单调（差值非负）、EWMA Gauge 随错误率收敛。

**验收场景**：
1. 给定 release 产生计数，当 scraper 读取，则 pass/block counter 单调，EWMA 暴露为 `ppm/1e6` Gauge。
2. 给定并发 add，当 exporter 仅读 sum()（禁用 reset），则无增量丢失。

---

### 边界情况

- 当单次调用真实 RT 超过 token `time` 字段可表时长（41 位≈69.7 年）会怎样？→ 不会发生（RT 被请求超时封顶）；模减法在此前提下正确。
- 当 acquire 与 release 跨越规则热换（version 不匹配）会怎样？→ 并发照常按 bucketIdx 回滚（稳定 state），EWMA 降权/跳过。
- 当 `Math.exp` 被误引入热路径会怎样？→ 违反性能红线，静态检查/评审拦截。
- 当令牌桶被错误分段会怎样？→ 全局 QPS 上限被破坏（误杀或放大），评审/契约测试拦截。
- 当 HALF_OPEN→CLOSED 后不清 EWMA 会怎样？→ 旧高错误率立即二次跳闸（v1 缺陷），由代际机制杜绝。

## 需求（必填）

### 功能性需求

- **FR-001**：系统必须以全局唯一整数 `resourceId` 标识资源，经数组寻址（BR-001）。
- **FR-002**：系统必须把可热换的纯参数（`ResourceConfig`）与长生命周期运行时状态（`ResourceState`）分离（BR-002）。
- **FR-003**：acquire 必须返回 64 位 long token（携带 time/version/bucketIdx/mask）或负阻断码（BR-003/004）。
- **FR-004**：引擎必须按能力位掩码 `0x01/0x02/0x04` 分派，任一失败返回对应阻断码（BR-005）。
- **FR-005**：限流必须以惰性令牌桶实现，维持全局 QPS 上限、不分段（BR-010..013）。
- **FR-006**：熔断必须以时间衰减 EWMA + 三态状态机实现，迁移递增 generation（BR-020..025）。
- **FR-007**：并发控制必须以分段近似实现，路由用 `ThreadLocalRandom` probe，bucketIdx 写入 token（BR-030..032）。
- **FR-008**：系统过载必须以分级概率 + 迟滞实现，前置短路（BR-040..042）。
- **FR-009**：热更新必须以 RCU 原子指针替换配置、状态稳定，release 版本校验（BR-050..052）。
- **FR-010**：release 必须不依赖执行线程（reactive 安全）（BR-060/061）。
- **FR-011**：观测计数必须只增不清、scraper 算差值，EWMA 暴露为 Gauge（BR-070..072）。
- **FR-012**：所有治理判定必须使用 `nanoTime` 相对单调时钟（BR-006）。

### 关键实体

- **ResourceConfig**：不可变纯参数（mask/rate/capacity/errThreshold/minCalls/openMillis/ewmaTau/concurrencyLimit/version），整体替换。
- **ResourceState**：聚合根，长生命周期（bucketState/breakerState/ewmaState AtomicLong + concurrency[] AtomicInteger + pass/block LongAdder）。
- **Token**：64 位 long 值对象（sign|time|version|bucketIdx|mask）。
- **BlockCode**：负数常量值对象（-1/-2/-3/-4）。

## 成功标准（必填）

### 可度量结果

- **SC-001**：`tryAcquire` 单线程基准 P50 < 100 ns；`release` P50 < 50 ns（JMH，现代 x86）。
- **SC-002**：acquire/release 热路径零字节堆分配（JMH `-gc`，`·gc.count` 增量为 0）。
- **SC-003**：四类治理能力各自功能正确——限流不超额、熔断三态闭环、并发上限生效、过载分级丢弃（各自集成测试通过）。
- **SC-004**：跨线程 release 并发段求和归零、热更新在途 release 计数零漂移（断言通过）。
- **SC-005**：覆盖率 行 ≥ 80% / 分支 ≥ 70% / 方法 ≥ 85%。
- **SC-006**：热路径静态无 `Math.exp`、无对象 `new`、无 `synchronized`（代码门控）。

## Clarifications（需求拷问结论，2026-07-30）

- v1 能力范围 → 四类全纳入（熔断/限流/并发/系统过载）
- 排除项 → 集群限流、热点参数限流、配置中心监听器（v1 仅 RCU + 编程式 API）
- 性能验收门槛 → SC-001/SC-002（P50<100ns/release<50ns、0 分配）
- Reactive/观测 → v1 含独立 reactive-adapter 模块 + 完整 Prometheus exporter

## 假设

- 单次调用真实 RT < token time 字段可表时长（模减法正确性前提）。
- 仅支持单机极速；集群秒级 QPS 同步、复杂调用链分析不在目标内。
- 被保护服务的请求超时为秒~分钟级，天然封顶 RT。
