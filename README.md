# Circuit Breaker

> 纳秒级、零堆分配、无锁化的 JVM 流量治理组件库（熔断 / 限流 / 并发控制 / 系统过载保护），对标 Alibaba Sentinel / Hystrix 的核心能力，以极致性能为第一性原理。

[![CI](https://github.com/jonyangx/circuit-breaker/actions/workflows/ci.yml/badge.svg)](https://github.com/jonyangx/circuit-breaker/actions/workflows/ci.yml) [![Java](https://img.shields.io/badge/Java-21%2B-blue)]() [![Gradle](https://img.shields.io/badge/Gradle-9.2.1-02303A)]() [![Tests](https://img.shields.io/badge/tests-37%20passed-brightgreen)]() [![tryAcquire](https://img.shields.io/badge/tryAcquire-~56ns%20%2F%200%20alloc-brightgreen)]()

## 为什么用它

传统流量治理组件（Sentinel/Hystrix）在提供丰富功能的同时引入显著开销：高频对象分配（GC 压力）、责任链深栈与滑动窗口锁竞争。本库通过**状态压缩、惰性计算、无锁 CAS、扁平化执行、配置/状态分离**，把单次治理调用压到**纳秒级、零堆分配**，适合网关、Service Mesh Sidecar、RPC 框架、连接池等高并发/资源受限场景。

**实测（JMH，JDK 21）**：`tryAcquire` ≈ **56 ns/op**，热路径 **零分配**（`gc.count ≈ 0`）。详见 [BENCHMARKS.md](BENCHMARKS.md)。

## 六条不变量（NON-NEGOTIABLE）

1. **纳秒级开销** — 单次 acquire/release 热路径在 ns 级。
2. **零堆分配** — 生命周期上下文压缩进 64 位 `long` token，热路径无对象。
3. **无锁** — 状态更新单次 `AtomicLong` CAS。
4. **无治理侧后台定时线程** — 惰性时间推导（仅观测/系统探针低频后台且非热路径）。
5. **配置/状态分离** — 配置可 RCU 热换，状态跨版本稳定。
6. **自描述 token** — token 携 version+bucketIdx，release 不依赖线程（reactive 安全）。

## 模块

| 模块 | 说明 |
|------|------|
| `circuit-breaker-core` | 共享内核 + 四能力（令牌桶/EWMA 熔断/分段并发/系统过载）+ 扁平执行引擎，**零三方依赖** |
| `circuit-breaker-reactive` | Reactor/WebFlux 操作符（跨线程 release 安全） |
| `circuit-breaker-observability` | Prometheus collector（pass/block counter + EWMA gauge） |
| `circuit-breaker-benchmarks` | JMH 微基准（SC-001/002 验证） |

## Quickstart

```java
// 注册资源（一次性）
int ORDER = ResourceManager.register("order_create",
    new PolicyBuilder()
        .enableRateLimit(1000)        // QPS
        .enableCircuitBreaker(0.5f)   // 错误率阈值
        .minimumCalls(20)
        .ewmaHalfLife(5000)           // ms
        .enableConcurrency(100)
        .build());

// 业务调用前后
long token = FlatExecutionEngine.tryAcquire(ORDER);
if (token < 0) {                       // 负数即阻断
    switch ((int) token) {
        case (int) BlockCode.RATE_LIMITER:      throw new RateLimitException();
        case (int) BlockCode.CIRCUIT_BREAKER:   throw new CircuitBreakerException();
        case (int) BlockCode.CONCURRENCY:       throw new ConcurrencyLimitException();
        case (int) BlockCode.SYSTEM_OVERLOAD:   throw new SystemOverloadException();
        default:                                 throw new IllegalStateException();
    }
}
boolean success = false;
try {
    success = doBusiness();
} finally {
    FlatExecutionEngine.release(ORDER, token, success);   // release 与执行线程无关
}
```

响应式（Reactor）：

```java
CircuitBreakerOperator.wrap(RESOURCE_ID, () -> doRemoteCall())
    .subscribe(...);   // 自动 acquire / doOnSuccess·doOnError release，跨线程安全
```

热更新（编程式 API）：

```java
ConfigSwapper.swap(ORDER, new ResourceConfig(0x03, 2000, 2000, 600_000, 20, 5000, 5000, 200, oldConfig.version + 1));
```

## 构建 / 测试 / 基准

```bash
./gradlew build                                       # 编译 + 全部测试（35 passed）
./gradlew :circuit-breaker-core:jacocoTestReport      # 覆盖率（core: 行90% / 分支76% / 方法98%）
./gradlew :circuit-breaker-benchmarks:jmh             # JMH 性能 + gc profiler
```

## 文档

- 设计（BRD，权威）：[docs/brd/design.md](docs/brd/design.md)
- 项目宪章：[memory/constitution.md](memory/constitution.md)
- 领域分析 / 用例 / 业务规则：[docs/domains/001-circuit-breaker/](docs/domains/001-circuit-breaker/)
- 架构与详细设计：[.eases/001-circuit-breaker/design/](.eases/001-circuit-breaker/design/)
- 基准结果：[BENCHMARKS.md](BENCHMARKS.md)

## 范围（v1）

**纳入**：熔断 / 限流 / 并发控制 / 系统过载 + reactive 适配 + Prometheus 导出 + RCU 热更新（编程式 API）。
**排除**：集群限流（需外置 Redis/Token Server）、热点参数限流、配置中心监听器（Nacos/Apollo/ETCD）。

## License

See [LICENSE](LICENSE).
