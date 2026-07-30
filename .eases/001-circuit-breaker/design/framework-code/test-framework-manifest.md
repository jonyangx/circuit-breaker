# 测试框架代码清单（test-framework-manifest，TDD）

**特性**：001-circuit-breaker | **日期**：2026-07-30
**约定**：测试方法体仅 `fail("TODO: ...")` 占位，关联 UC/BR；TDD 红-绿-重构（先红）。

## TDD 实现顺序（按依赖关系）

| 序号 | 测试类 | 对应生产类 | 测试方法数 | 关联 UC | 覆盖重点 |
|------|--------|-----------|-----------|---------|----------|
| 1 | core/TokenCodecTest | TokenCodec | 3 | UC-002/003 | 位布局/符号位/模减 |
| 2 | core/FlatExecutionEngineRateLimitTest | FlatExecutionEngine | 2 | UC-002/004 | bitmask 分派/限流端到端 |
| 3 | core/ratelimit/LazyTokenBucketTest | LazyTokenBucket | 4 | UC-004 | 限流/容量/抹零 |
| 4 | core/breaker/EwmaAlphaTest | EwmaAlpha | 3 | UC-005 | α 分段误差 |
| 5 | core/breaker/EwmaCircuitBreakerTest | EwmaCircuitBreaker | 5 | UC-005 | 三态/代际 |
| 6 | core/concurrency/SegmentedConcurrencyTest | SegmentedConcurrency | 3 | UC-006 | 上限/回滚/路由 |
| 7 | core/system/SystemOverloadTest | SystemOverload | 3 | UC-007 | 分级/迟滞 |
| 8 | core/reload/ConfigSwapperTest | ConfigSwapper | 3 | UC-008 | 生效/在途/稳定 |
| 9 | reactive/CircuitBreakerOperatorTest | CircuitBreakerOperator | 2 | UC-009 | 跨线程/阻断 |
| 10 | observability/CircuitBreakerCollectorTest | CircuitBreakerCollector | 3 | UC-010 | 单调/Gauge/非阻塞 |

## 覆盖率目标（ease-testing 标准）
- 行覆盖率：≥ 80%
- 分支覆盖率：≥ 70%
- 方法覆盖率：≥ 85%

## 备注
- 全部测试初始 `fail("TODO")` → 运行即红（TDD RED）；Phase 3 逐个转绿。
- 资源层隔离：注入 FakeClock（ClockSource 可测试性）；系统过载用测试钩子写 SHED_PERMILLE。
- 集成测试（跨线程/热更新）单独置于 core 模块 integration 包（Phase 3 补）。
