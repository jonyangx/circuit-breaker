# 任务清单（Phase 2 视图）：Circuit Breaker

**特性分支**：`001-circuit-breaker` | **日期**：2026-07-30
**权威任务清单**：`../flow-1-analyze/tasks.md`（42 任务，T001..T042，TDD）
**本文件作用**：Phase 2 tasks 阶段——记录 Phase 2 对任务的贡献，指向权威清单（单一事实来源，stage-contract C-4）。

## Phase 2 贡献（框架骨架已产出，对应以下权威任务的"骨架"部分）

| 权威任务 | 骨架文件（framework-code/） | 状态 |
|----------|----------------------------|------|
| T005 TokenCodec 测试 | src/test/.../TokenCodecTest.java | 骨架✅（fail 占位） |
| T006 TokenCodec | src/main/.../TokenCodec.java | 骨架✅（TODO） |
| T007 BlockCode | src/main/.../BlockCode.java | 骨架✅ |
| T008 ClockSource | src/main/.../ClockSource.java | 骨架✅ |
| T009 ResourceConfig | src/main/.../ResourceConfig.java | 骨架✅ |
| T010 ResourceState | src/main/.../ResourceState.java | 骨架✅ |
| T011 ResourceManager | src/main/.../ResourceManager.java | 骨架✅ |
| T012 FlatExecutionEngine | src/main/.../FlatExecutionEngine.java | 骨架✅ |
| T013/015/016/017 限流 | LazyTokenBucket.java + tests | 骨架✅ |
| T018..022 熔断 | EwmaAlpha/EwmaCircuitBreaker + tests | 骨架✅ |
| T023..025 并发 | SegmentedConcurrency + test | 骨架✅ |
| T026..028 系统过载 | SystemOverload + test | 骨架✅ |
| T029..031 热更新 | ConfigSwapper + test | 骨架✅ |
| T032/033 reactive | CircuitBreakerOperator + test | 骨架✅ |
| T034/035 observability | CircuitBreakerCollector + test | 骨架✅ |
| T036/037 benchmarks | AcquireReleaseBenchmark | 骨架✅ |

## Phase 3 待办
实现 `framework-code/` 下所有 TODO 方法体（红→绿→重构），迁入各 Gradle 模块实际路径，就地回写 `../flow-1-analyze/tasks.md` 的 `[ ]`→`[X]`。

> ⚠️ Phase 3 仅 `implement`，不重跑 specify→plan→tasks（stage-contract C-4）。
