# 接口文档报告：Circuit Breaker（Java 公共 API）

**特性分支**：`001-circuit-breaker` | **日期**：2026-07-30
**关联架构**：`architecture/arch-design.md` | **关联详细设计**：`detail/detail-design.md`
**开发模式**：全新开发（DEVELOPMENT_MODE=new）| **需求模式**：new-domain | **状态**：Draft

> 本组件为嵌入式 JVM 库，"接口"为 **Java 公共方法**（非 HTTP/REST）。下表以「方法签名 + 契约 + 阻断语义」描述公共 API 面。

## 1. 接口变更总览

### 1.1 接口分类统计

| 分类 | 数量 | 说明 |
|------|------|------|
| 新增接口（公共方法） | 7 | 本次全新创建的公共 API |
| 修改接口 | 0 | greenfield |
| 涉及的现有接口 | 0 | greenfield |

### 1.2 接口清单（公共 API）

| 编号 | 类.方法 | 变更类型 | 关联用例 | 优先级 | 简要描述 |
|------|---------|----------|----------|--------|----------|
| API-001 | `ResourceManager.register(String, Policy): int` | 新增 | UC-001 | P1 | 注册资源，返回 resourceId |
| API-002 | `FlatExecutionEngine.tryAcquire(int): long` | 新增 | UC-002 | P1 | 获取治理令牌（<0 阻断） |
| API-003 | `FlatExecutionEngine.release(int, long, boolean)` | 新增 | UC-003 | P1 | 释放并上报结果 |
| API-004 | `PolicyBuilder.enableRateLimit(long)/enableCircuitBreaker(float)/enableConcurrency(int)/...build()` | 新增 | UC-001 | P1 | 构建能力策略 |
| API-005 | `ConfigSwapper.swap(int, ResourceConfig)` | 新增 | UC-008 | P3 | RCU 热更新 |
| API-006 | `CircuitBreakerOperator.<T>wrap(int, Supplier<Mono<T>>): Mono<T>` | 新增 | UC-009 | P3 | 响应式治理包装 |
| API-007 | `CircuitBreakerCollector.register(registry, int...)` | 新增 | UC-010 | P3 | Prometheus 注册 |

> 阻断语义（全 API 共享）：`tryAcquire` 返回负 long = 阻断（-1 过载/-2 熔断/-3 限流/-4 并发，BR-004）；非热路径非法入参抛 `IllegalArgumentException`。

## 2. 新增接口详细规范

### 2.1 API-002: `FlatExecutionEngine.tryAcquire(int resourceId): long`

#### 基本信息
| 属性 | 值 |
|------|-----|
| 签名 | `public static long tryAcquire(int resourceId)` |
| 描述 | 系统过载前置短路 → bitmask 分派四能力 → 打包 token 或返回负阻断码 |
| 关联用例 | UC-002（获取治理令牌） |
| 线程安全 | 是（无锁 CAS） |
| 分配 | 零字节（热路径不变量2） |

#### 请求/响应
- 入参：`resourceId`（0..1023，注册所得）。
- 返回：`>=0` token（携带 time/version/bucketIdx/mask）；`<0` 阻断码。
- 异常：`resourceId` 越界/未注册 → `IllegalArgumentException`（非热路径）。

#### 错误码表
| 错误码（返回值） | 触发条件 | 关联规则 |
|------------------|----------|----------|
| -1 SYSTEM_OVERLOAD | `SHED_PERMILLE>0` 且随机命中 | BR-040 |
| -2 CIRCUIT_BREAKER | 熔断 OPEN（未到期）/ HALF_OPEN 非探路 | BR-025 |
| -3 RATE_LIMITER | 令牌不足 | BR-011 |
| -4 CONCURRENCY | 并发达上限 | BR-030 |

#### 测试上下文（供测试 agent）
> 权威测试案例见 `test-case-document.md` §4（TC-API-002-NNN：正常放行 + 四类阻断 + 零分配）。
- **Mock 策略**：注入可控 `ClockSource`（FakeClock 推进时间）验证令牌补充/EWMA 衰减；系统过载用反射/测试钩子写 `SHED_PERMILLE`。
- **场景**：正常放行、限流阻断、熔断 OPEN/HALF_OPEN 阻断、并发阻断、过载概率阻断、非法 resourceId 抛异常。

### 2.2 API-003: `FlatExecutionEngine.release(int resourceId, long token, boolean success)`

| 属性 | 值 |
|------|-----|
| 签名 | `public static void release(int resourceId, long token, boolean success)` |
| 描述 | 解码 token → 并发回滚 + EWMA 上报 + 熔断迁移 + 计数 |
| 关联用例 | UC-003 | 线程安全 | 是（线程无关） | 分配 | 零字节 |

- 入参：`resourceId`、`token`（acquire 所得，>=0）、`success`（业务调用结果）。
- 行为：mask&0x04→并发[bidx]-1；mask&0x01→breaker.release（含版本校验）；计数递增。
- **关键**：不依赖执行线程（BR-060），reactive 安全。

#### 测试上下文
> 权威案例 `TC-API-003-NNN`（跨线程回滚归零 + 版本不匹配降权 + 熔断迁移）。
- **Mock**：FakeClock；多线程 `ExecutorService` 模拟 Reactor 线程切换。

### 2.3 API-001: `ResourceManager.register(String name, Policy policy): int`

| 属性 | 值 |
|------|-----|
| 签名 | `public static int register(String name, Policy policy)` |
| 描述 | 分配全局唯一 resourceId，初始化 CONFIGS[id]/STATES[id] |
| 关联用例 | UC-001 |

- 返回：`resourceId`（0..1023）。
- 异常：资源数超 1024 → `IllegalStateException`。

### 2.4 API-004: `PolicyBuilder` 链式构建
`enableRateLimit(long qps)` / `enableCircuitBreaker(float errThreshold)` / `minimumCalls(int)` / `ewmaHalfLife(long ms)` / `enableConcurrency(int limit)` / `build(): Policy`。产出不可变 `ResourceConfig`（含 mask + version）。

### 2.5 API-005: `ConfigSwapper.swap(int resourceId, ResourceConfig newConfig)`
原子替换 `CONFIGS[rid]`（version+1），STATES 不动。下次 acquire 新参数生效。

### 2.6 API-006: `CircuitBreakerOperator.wrap(int resourceId, Supplier<Mono<T>> source): Mono<T>`
`Mono.defer`→acquire，`<0`→`Mono.error`，否则 `doOnSuccess/doOnError`→release。

### 2.7 API-007: `CircuitBreakerCollector.register(Object registry, int... resourceIds)`
注册 pass/block Counter + EWMA Gauge；只读 `LongAdder.sum()`，禁 reset。

## 3. 修改接口变更说明
（greenfield，无）

## 4. 涉及的现有接口
（greenfield，无）

## 5. 跨接口依赖与调用链路

```
业务 → API-002 tryAcquire ─┬─> (core 内) breaker/bucket/concurrency
                           └─> 返回 token
业务 → API-003 release ────┬─> concurrency[bidx] 回滚
                           ├─> breaker.release（迁移/EWMA）
                           └─> LongAdder 计数 → API-007 导出
API-006 reactive → API-002/003（跨线程）
API-005 swap → 仅改 CONFIGS，影响 API-002 下次读与 API-003 版本校验
```

## 6. 接口影响分析
| 维度 | 评估 |
|------|------|
| 向后兼容 | greenfield 无消费者；位布局/token 编码为 v1 契约，后续变更需 MAJOR |
| 回归风险 | API-002/003 为全能力公共入口，变更影响所有用例 → CI 性能门控 + 集成测试守护 |
| 下游消费者 | reactive/observability 模块仅消费 core 公共 API，core 内部位布局变更需同步 |
