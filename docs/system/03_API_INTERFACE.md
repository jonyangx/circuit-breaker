# 03 API 接口（API Interface）

> 本库为嵌入式 JVM 店，"接口"为 **Java 公共方法**（非 HTTP）。证据 `文件:行`。
> 与 `.eases/001-circuit-breaker/design/architecture/api-interface-report.md` 对齐（已按合并后代码更新）。

## 1. 公共 API 清单

| 编号 | 类.方法 | 签名 | 关联 UC | 说明 |
|---|---|---|---|---|
| API-001 | `ResourceManager.register` | `static synchronized int (String name, ResourceConfig config)` | UC-001 | 注册资源→resourceId |
| API-002 | `FlatExecutionEngine.tryAcquire` | `static long (int resourceId)` | UC-002 | 获取治理令牌（<0 阻断） |
| API-003 | `FlatExecutionEngine.release` | `static void (int resourceId, long token, boolean success)` | UC-003 | 释放并上报 |
| API-004 | `PolicyBuilder.build` | `ResourceConfig ()` | UC-001 | 构建不可变配置（含校验） |
| API-005 | `ConfigSwapper.swap` | `static void (int resourceId, ResourceConfig newConfig)` | UC-008 | RCU 热更新 |
| API-006 | `CircuitBreakerOperator.wrap` | `static <T> Mono<T> (int resourceId, Supplier<Mono<T>> source)` | UC-009 | 响应式治理 |
| API-007 | `CircuitBreakerCollector.register` | `static void (CollectorRegistry registry, int... resourceIds)` | UC-010 | Prometheus 注册 |

## 2. 核心方法规范

### API-002 tryAcquire（`FlatExecutionEngine.java:21`）
- **入参**：`resourceId`（0..1023，注册所得）。
- **返回**：`>=0` token（携带 time/version/bucketIdx/mask）；`<0` 阻断码。
- **错误语义**：阻断以负 long 返回（不抛异常，零分配）；`resourceId` 越界/未注册抛 `IllegalArgumentException`。
- **阻断码**：`-1` 系统过载 / `-2` 熔断 / `-3` 限流 / `-4` 并发（`BlockCode`）。
- **线程安全**：无锁 CAS；零分配。

### API-003 release（`FlatExecutionEngine.java:57`）
- **入参**：`resourceId`、`token`（acquire 所得 ≥0）、`success`（业务结果）。
- **行为**：mask&0x04→并发按 bucketIdx 回滚；mask&0x01→`EwmaCircuitBreaker.release`（含 version 校验）；计数递增。
- **关键**：release **不依赖执行线程**（token 自描述，reactive 安全）。

### API-004 PolicyBuilder.build（`PolicyBuilder.java`）
链式 `enableRateLimit(qps)` / `enableCircuitBreaker(float)` / `enableConcurrency(int)` / `minimumCalls(int)` / `ewmaHalfLife(ms)` / `openMillis(ms)` → `build()`。**入参校验**：errThreshold∈(0,1]、τ>0、qps∈(0,4_194_303]、minCalls>0、concurrency>0、openMillis>0，违例抛 `IllegalArgumentException`。

### API-005 ConfigSwapper.swap（`ConfigSwapper.java`）
`swap(rid, newConfig)` → `ResourceManager.publishConfig`；version 由调用方 +1；STATES 不动；下次 acquire 新参数生效。

### API-006 CircuitBreakerOperator.wrap（`CircuitBreakerOperator.java:20`）
`Mono.defer`→tryAcquire；<0→`Mono.error(GovernanceException.forToken(token))`；否则 `doOnSuccess/doOnError`→release。

### API-007 CircuitBreakerCollector.register（`CircuitBreakerCollector.java:26`）
注册 Prometheus counter（pass/block，族名 `circuit_breaker_calls`）+ gauge（`circuit_breaker_error_rate` = ppm/1e6）；只读 `ResourceState`。

## 3. 异常模型
`GovernanceException`（base，`serialVersionUID=1`）+ 4 子类。`forToken(long)` 返回 / `throwFor(long)` 抛出。典型用法：
```java
long t = FlatExecutionEngine.tryAcquire(rid);
if (t < 0) throw GovernanceException.throwFor(t);
```

## 4. 兼容性
- 位布局常量（`TokenCodec *_BITS/*_SHIFT`）、状态字位布局为 v1 契约；变更属 MAJOR 破坏性版本。
- API-002/003 为全能力公共入口，变更影响所有 UC；CI + 集成测试守护。
