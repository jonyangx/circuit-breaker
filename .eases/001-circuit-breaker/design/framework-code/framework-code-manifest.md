# 框架代码清单（framework-code-manifest）

**特性**：001-circuit-breaker | **模式**：全新开发（DEVELOPMENT_MODE=new）| **日期**：2026-07-30
**约定**：方法体仅 `throw new UnsupportedOperationException("TODO: ...")` 占位，引用 UC/BR；不含业务逻辑（constitution Phase Content Boundaries）。

## 生产代码骨架（src/main/java/dev/circuitbreaker）

| 文件 | 职责 | 关联 UC/BR |
|------|------|-----------|
| core/TokenCodec.java | 64 位 token 编解码 | UC-002/003 BR-003 |
| core/ClockSource.java | 单调相对时钟 | 全部 BR-006 |
| core/BlockCode.java | 阻断码常量 | UC-002 BR-004 |
| core/ResourceConfig.java | 不可变配置 | UC-001 BR-002/050 |
| core/ResourceState.java | 聚合根（稳定状态） | UC-001 BR-002/051 |
| core/ResourceManager.java | 注册 + 寻址 | UC-001 BR-001 |
| core/PolicyBuilder.java | 策略构建器 | UC-001 |
| core/Policy.java | 策略封装 | UC-001 |
| core/FlatExecutionEngine.java | 引擎 + bitmask 分派 | UC-002/003 BR-005 |
| core/ratelimit/LazyTokenBucket.java | 惰性令牌桶 | UC-004 BR-010..013 |
| core/breaker/EwmaAlpha.java | α 分段近似 | UC-005 BR-021 |
| core/breaker/EwmaCircuitBreaker.java | 三态状态机 + 代际 | UC-005 BR-020/022/023/024/025 |
| core/concurrency/SegmentedConcurrency.java | 分段并发 | UC-006 BR-030/031/032 |
| core/system/SystemOverload.java | 分级丢弃 + 迟滞 | UC-007 BR-040/041/042 |
| core/reload/ConfigSwapper.java | RCU 热更新 | UC-008 BR-050/051/052 |
| reactive/CircuitBreakerOperator.java | Reactor 包装 | UC-009 BR-060/061 |
| observability/CircuitBreakerCollector.java | Prometheus 导出 | UC-010 BR-070/071/072 |
| benchmarks/AcquireReleaseBenchmark.java | JMH 性能验证 | SC-001/002 |

## 统计
- 生产骨架文件：18（core 13 + reactive 1 + observability 1 + benchmarks 1 + Policy 1 ... 见上）
- 全部方法体：`throw UnsupportedOperationException("TODO")` 占位
- Phase 3（flow-3-implement）将就地实现 TODO，骨架迁入各模块实际路径
