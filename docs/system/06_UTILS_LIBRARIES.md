# 06 工具库与可观测性（Utils & Libraries）

> 来源：`src/main/java/dev/circuitbreaker/core/{ClockSource,TokenCodec,BlockCode,GovernanceException}.java`、`observability/`、`reactive/`、`build.gradle.kts`

## 1. 核心工具类

### 1.1 ClockSource（`ClockSource.java`）
`nowRelMs()` = `System.nanoTime()/1_000_000 − START`（`START` 类加载时记录）。所有治理判定的相对单调时钟；禁用 `currentTimeMillis()` 做治理判定（不变量 / BR-006）。

### 1.2 TokenCodec（`TokenCodec.java`）
64 位 token encode/decode + `rtMs` 模减法；位宽/偏移/掩码编译期常量；无分支无对象。详见 `04_DATA_MODEL.md` §4。

### 1.3 BlockCode（`BlockCode.java`）
阻断码常量：`SYSTEM_OVERLOAD=-1`、`CIRCUIT_BREAKER=-2`、`RATE_LIMITER=-3`、`CONCURRENCY=-4`。

### 1.4 GovernanceException（`GovernanceException.java`）
块码→类型化异常唯一映射（base + 4 子类，带 serialVersionUID）；`forToken` 返回 / `throwFor` 抛出。详见 `03_API_INTERFACE.md` §3。

### 1.5 ArchUnit 静态守护（测试侧）
`HotPathGuardTest`（`src/test/.../core/HotPathGuardTest.java`）：禁热路径 `Math.exp`（除 EwmaAlpha LUT 初始化）/ `synchronized` 方法（除 ResourceManager.register）。固化零分配/无锁不变量。

## 2. 可观测性（observability / `CircuitBreakerCollector`）

### 2.1 指标
| 指标 | 类型 | 含义 | 来源 |
|---|---|---|---|
| `circuit_breaker_calls_total` | Counter（族名 `circuit_breaker_calls`） | pass/block 计数，单调 | `ResourceState.passCount/blockCount` |
| `circuit_breaker_error_rate` | Gauge | EWMA 错误率 0..1 | `ResourceState.ewmaErrorRatePpm()/1e6` |

- **单调**：LongAdder 只增不清；scraper 端算差值，**禁 `reset()`**（与并发 add 竞态丢增量）。
- **非阻塞**：scrape 只读 `sum()`，不进 acquire/release 热路径。

### 2.2 热路径日志策略
acquire/release 热路径**禁止日志**（避免分配）；仅注册/热更新/异常路径可结构化日志。

## 3. 外部依赖（`build.gradle.kts`）

| 依赖 | 版本 | 作用域 | 用途 |
|---|---|---|---|
| `io.projectreactor:reactor-core` | 3.6.10 | implementation | reactive 模块 |
| `io.projectreactor:reactor-test` | 3.6.10 | testImplementation | reactive 测试 |
| `io.prometheus:simpleclient` | 0.16.0 | implementation | observability 模块 |
| `org.openjdk.jmh:jmh-core` / `jmh-generator-annprocess` | 1.37 | jmh | benchmarks |
| `org.junit.jupiter:junit-jupiter` | 5.10.2 | test | 测试框架 |
| `org.assertj:assertj-core` | 3.26.3 | test | 断言 |
| `com.tngtech.archunit:archunit-junit5` | 1.3.0 | test | 静态守护 |

> core 包**零三方依赖**（不变量：可被任何中间件内嵌）；reactive/observability 为可选依赖（按包隔离）。

## 4. 构建工具与脚本
- Gradle wrapper 9.2.1（`gradlew`/`gradlew.bat`，mode 100755；`gradle/wrapper/*`）。
- 任务：`build`（编译+测试）、`jacocoTestReport`（覆盖率）、`jmh`（基准 + `-prof gc`）。
- CI：`.github/workflows/ci.yml`（JDK 21 / Linux，push+PR）。

## 5. 性能基线（JMH，`BENCHMARKS.md`）
`tryAcquire` ≈ 56 ns/op；`acquireRelease` ≈ 131 ns/op；热路径 `gc.count ≈ 0`（零分配）。由 `benchmarks/AcquireReleaseBenchmark` 产出。
