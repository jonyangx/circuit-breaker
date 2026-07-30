# 测试案例文档：Circuit Breaker

**特性分支**：`001-circuit-breaker` | **日期**：2026-07-30
**关联**：`arch-design.md`、`detail-design.md`、`api-interface-report.md`、`usecases/`
**测试技术**：等价类划分 + 边界值分析 + 状态迁移测试 + 决策表（MC/DC）+ 并发压力测试 + 性能基准（JMH）

## 1. 测试范围

覆盖 7 个公共 API（API-001..007，接口覆盖率 100%）、四类治理能力状态机、配置-状态分离与热更新正确性、reactive 跨线程安全、性能红线。

## 2. 追溯矩阵（UC/BR/API → 测试案例）

| UC/BR | API | 覆盖案例 |
|-------|-----|----------|
| UC-001/BR-001 | API-001 | TC-API-001-001..003 |
| UC-002/BR-003/004/005 | API-002 | TC-API-002-001..007 |
| UC-003/BR-060/052 | API-003 | TC-API-003-001..005 |
| UC-004/BR-010..013 | API-002 | TC-CAP-RL-001..004 |
| UC-005/BR-020..025 | API-002/003 | TC-CAP-CB-001..006 |
| UC-006/BR-030..032 | API-002/003 | TC-CAP-CC-001..003 |
| UC-007/BR-040..042 | API-002 | TC-CAP-SO-001..003 |
| UC-008/BR-050..052 | API-005 | TC-API-005-001..003 |
| UC-009/BR-060/061 | API-006 | TC-API-006-001..002 |
| UC-010/BR-070..072 | API-007 | TC-API-007-001..003 |
| SC-001/002 | API-002/003 | TC-PERF-001..003 |

## 3. 风险驱动优先级
- **P1**：token 编解码正确性、限流不超额、熔断三态闭环 + 代际、零分配、跨线程回滚归零。
- **P2**：并发上限、系统过载分级迟滞、热更新在途零漂移。
- **P3**：reactive 包装、Prometheus 单调导出、JMH 门控。

## 4. 测试案例详情（Given-When-Then）

### 4.1 API-001 register（UC-001）
- **TC-API-001-001 [P1]**：Given 空注册表，When register("order", rateLimitPolicy)，Then 返回 0；CONFIGS[0]/STATES[0] 非空。
- **TC-API-001-002 [P1]**：Given 已注册 1024 个，When 第 1025 次 register，Then 抛 IllegalStateException。
- **TC-API-001-003 [P2]**：Given 同名二次注册，When register，Then 返回新 resourceId（不报错，按编号递增）。

### 4.2 API-002 tryAcquire（UC-002，BR-003/004/005）
- **TC-API-002-001 [P1 正常]**：Given 资源未达任何上限，When tryAcquire，Then 返回 >=0 且 decodeVersion=cfg.version、decodeMask=cfg.mask。
- **TC-API-002-002 [P1 边界-mask]**：Given mask=0x02 only，When 限流触发，Then 返回 -3（不触发熔断/并发判定）。
- **TC-API-002-003 [P1 异常-resourceId]**：Given resourceId=9999，When tryAcquire，Then 抛 IllegalArgumentException。
- **TC-API-002-004 [P2 并发]**：Given 100 线程并发 tryAcquire 同资源，Then 无异常、返回值均为合法 token 或负码（线程安全）。
- **TC-API-002-005 [P1 边界-符号位]**：Given 任意放行，Then 返回 token 符号位=0（token>=0）。
- **TC-API-002-006 [P2 错误-过载]**：Given SHED_PERMILLE=1000，When tryAcquire，Then 必返回 -1。
- **TC-API-002-007 [P2 错误-过载0]**：Given SHED_PERMILLE=0，Then 零拦截、进入资源策略。

### 4.3 API-003 release（UC-003，BR-060/052）
- **TC-API-003-001 [P1 正常]**：Given 已 acquire，When release(token,true)，Then 并发段求和归零、passCount+1。
- **TC-API-003-002 [P1 跨线程]**：Given acquire 在线程A，When release 在线程B（不同 bucketIdx 路由），Then concurrency 正确归零（零漂移，BR-060）。
- **TC-API-003-003 [P1 版本不匹配]**：Given acquire 后热换（version 变），When release，Then 并发照常回滚、EWMA 降权/跳过（BR-052）。
- **TC-API-003-004 [P2 异常-重复 release]**：Given 已 release，When 再次 release 同 token，Then 并发计数变负被断言/记录（防误用）。
- **TC-API-003-005 [P1 正常-RT 模减]**：Given token 携 time，When release 计算 rtMs，Then rtMs=(now−decodeTime)&TIME_MASK 正确。

### 4.4 能力-限流（UC-004，BR-010..013）
- **TC-CAP-RL-001 [P1]**：Given rate=1000/ms，When 2000/ms 调用，Then 约 1000 放行、其余 -3，令牌不超额。
- **TC-CAP-RL-002 [P1 边界-容量截断]**：Given capacity=10，空闲后首波，Then 最多放行 10（BR-011 min 截断）。
- **TC-CAP-RL-003 [P1 抹零]**：Given rate=1/s（0.001/ms），空闲 1000ms，When 调用，Then 令牌能生成（BR-013 推进判定）。
- **TC-CAP-RL-004 [P2]**：Given 持续超额，Then Time_last 仅在生成完整令牌时推进。

### 4.5 能力-熔断（UC-005，BR-020..025）
- **TC-CAP-CB-001 [P1 跳闸]**：Given CLOSED/minCalls=20/thr=50%，连续失败 20+，Then →OPEN，acquire=-2。
- **TC-CAP-CB-002 [P1 半开探路]**：Given OPEN 到期，When 首个 acquire，Then 唯一线程 →HALF_OPEN 放行，其余 -2。
- **TC-CAP-CB-003 [P1 恢复]**：Given HALF_OPEN 探路 release(success)，Then →CLOSED，acquire 放行。
- **TC-CAP-CB-004 [P1 代际-不二次跳闸]**：Given HALF_OPEN→CLOSED 后立即调用，Then 旧高 ppm 不立即二次跳闸（代际重播种，BR-024）。
- **TC-CAP-CB-005 [P1 探路失败]**：Given HALF_OPEN release(fail)，Then →OPEN（重置 endTime）。
- **TC-CAP-CB-006 [P2 α 近似]**：u∈(1/128,8) 段 α 绝对误差 ≤ ~3e-5；u≤1/128 α≈u；u≥8 α=1（BR-021）。

### 4.6 能力-并发（UC-006，BR-030..032）
- **TC-CAP-CC-001 [P1 上限]**：Given limit=10，20 并发 acquire（不 release），Then 约 10 放行、其余 -4。
- **TC-CAP-CC-002 [P1 回滚归零]**：全部 release 后，Then sum(concurrency)=0。
- **TC-CAP-CC-003 [P2 路由]**：acquire 返回 token 解码 bucketIdx ∈ [0,SEG)。

### 4.7 能力-系统过载（UC-007，BR-040..042）
- **TC-CAP-SO-001 [P2 分级]**：SHED_PERMILLE=500，Then 约 50% 顶层 -1（不进资源策略）。
- **TC-CAP-SO-002 [P2 迟滞]**：CPU 升降跨阈值，Then 进退档阈值不同、无抖动（BR-041）。
- **TC-CAP-SO-003 [P3]**：SHED_PERMILLE=0 时零开销。

### 4.8 API-005 热更新（UC-008，BR-050..052）
- **TC-API-005-001 [P2 生效]**：热换 rate 1000→2000，Then 下次 acquire 按新容量。
- **TC-API-005-002 [P1 在途零漂移]**：acquire/release 间高频热换，Then 并发求和归零、无负值。
- **TC-API-005-003 [P2 状态稳定]**：热换后 STATES[id] 同一对象（identity 不变，BR-051）。

### 4.9 API-006 reactive（UC-009，BR-060/061）
- **TC-API-006-001 [P3 正常]**：wrap(Mono) 成功路径，Then release(success) 在某 Reactor 线程执行，计数归零。
- **TC-API-006-002 [P3 阻断]**：acquire=-3，Then Mono.error(RateLimitException)。

### 4.10 API-007 导出（UC-010，BR-070..072）
- **TC-API-007-001 [P3 单调]**：连续 pass/block，Then Counter 差值非负（BR-071 不 reset）。
- **TC-API-007-002 [P3 Gauge]**：EWMA 随错误率收敛，Gauge=ppm/1e6。
- **TC-API-007-003 [P3 非阻塞]**：scrape 期间 acquire/release 无尾延迟劣化。

### 4.11 性能（SC-001/002）
- **TC-PERF-001 [P1 纳秒]**：JMH tryAcquire 单线程 P50 < 100ns。
- **TC-PERF-002 [P1 纳秒]**：JMH release P50 < 50ns。
- **TC-PERF-003 [P1 零分配]**：JMH `-gc` acquire/release `·gc.count` 增量=0。

## 5. 覆盖率与门控
- 接口覆盖率 = 100%（API-001..007 每个 ≥1 标准合格 testcase；新增档：正常+参数+错误+并发）。
- `test-case-document.md §4` 覆盖接口集合 == `api-interface-report.md §1.2`（7/7，一一对应）。
- 代码覆盖率目标：行≥80%/分支≥70%/方法≥85%。
