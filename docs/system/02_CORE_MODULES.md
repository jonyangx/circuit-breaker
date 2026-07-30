# 02 核心模块（Core Modules）

> 来源：`src/main/java/dev/circuitbreaker/**` · 证据 `文件:符号`

## 1. 模块总览

| 模块（包） | 职责 | 关键类 |
|---|---|---|
| `core`（共享内核） | 资源寻址、token、时钟、引擎、配置、异常 | `FlatExecutionEngine`、`ResourceManager`、`TokenCodec`、`ResourceConfig/State`、`PolicyBuilder`、`GovernanceException`、`ClockSource`、`BlockCode` |
| `core/breaker` | 时间衰减 EWMA 熔断 + 三态状态机 | `EwmaCircuitBreaker`、`EwmaAlpha` |
| `core/ratelimit` | 惰性无锁令牌桶 | `LazyTokenBucket` |
| `core/concurrency` | 分段近似并发 | `SegmentedConcurrency` |
| `core/system` | 分级过载丢弃 + 迟滞 | `SystemOverload` |
| `core/reload` | RCU 配置热更新 | `ConfigSwapper` |
| `reactive` | Reactor/WebFlux 适配 | `CircuitBreakerOperator` |
| `observability` | Prometheus 导出 | `CircuitBreakerCollector` |

## 2. 共享内核 `dev.circuitbreaker.core`

### 2.1 FlatExecutionEngine（公共入口 + bitmask 分派）
- `tryAcquire(int resourceId): long`（`FlatExecutionEngine.java:21`）：系统过载前置短路 → bitmask 分派 0x01/0x02/0x04 → 打包 token 或返回负阻断码。
- `release(int resourceId, long token, boolean success)`（`:57`）：解码 token → 并发回滚 + EWMA 上报/熔断迁移 + 计数。
- 依赖：`ResourceManager`（CONFIGS/STATES）、`ClockSource`、四能力类。

### 2.2 ResourceManager（注册 + 数组寻址）
- `register(String, ResourceConfig): int`（`ResourceManager.java:19`，synchronized）→ 分配 resourceId（0..1023），建 STATES[id]，seed 令牌桶，发布 CONFIGS[id]。
- `publishConfig(int, ResourceConfig)`（`:51`）：RCU 受控入口（ConfigSwapper 用）。
- `CONFIGS`: `AtomicReferenceArray<ResourceConfig>`（安全发布）；`STATES`: `final ResourceState[]`（set-once，跨版本稳定）。

### 2.3 TokenCodec（64 位 token）
位布局 `[sign:1][time:41][version:6][bucketIdx:4][mask:12]`；`encode/decode*`（`TokenCodec.java`）；RT 模减法 `rtMs`。无分支、无对象。

### 2.4 ResourceConfig（不可变实体）/ ResourceState（聚合根）
- `ResourceConfig`: 9 个 `public final` 字段（mask/qps/capacity/errThresholdPpm/minCalls/openMillis/ewmaTauMs/concurrencyLimit/version）。
- `ResourceState`: 聚合根，`public final` AtomicLong（bucketState/breakerState/ewmaState）+ AtomicInteger[16] concurrency + LongAdder pass/block；`ewmaErrorRatePpm()` 只读访问器。详见 `04_DATA_MODEL.md`。

### 2.5 PolicyBuilder（策略构建 + 校验）
链式 `enableRateLimit/enableCircuitBreaker/enableConcurrency/minimumCalls/ewmaHalfLife/openMillis` → `build()`（`PolicyBuilder.java`）做入参校验（errThreshold∈(0,1]、τ>0、qps∈(0,4_194_303]、minCalls>0、concurrency>0、openMillis>0）。

### 2.6 GovernanceException（块码→类型化异常）
`forToken(long)` 返回 / `throwFor(long)` 抛出（`GovernanceException.java`）；base + 4 子类（RateLimited/CircuitOpen/ConcurrencyLimited/SystemOverloaded），均带 serialVersionUID。

## 3. 四能力（经 bitmask 分派）

### 3.1 core/breaker — EwmaCircuitBreaker
- `tryAcquire(st, cfg, nowMs)`（`EwmaCircuitBreaker.java:31`）：CLOSED 放行；OPEN 到期→HALF_OPEN 单探路；HALF_OPEN 探路截止→惰性回退 OPEN **自愈**。
- `release(st, nowMs, ok, cfg, verMatch)`（`:49`）：HALF_OPEN→CLOSED/OPEN；CLOSED→updateEwma+跳闸判定。
- `transition()` 唯一改 generation；`updateEwma` 代际不匹配重播种。
- `EwmaAlpha.alpha(dt,τ)` 分段近似（无 Math.exp）。

### 3.2 core/ratelimit — LazyTokenBucket
`tryAcquire(st, cfg, nowMs)`（`LazyTokenBucket.java:29`）：高42位Time|低22位Tokens，惰性补 `add=(now-tLast)*qps/1000`，nTok cap TOKEN_MASK，nTok<1 不推进 tLast（抹零）。`seed()` 注册时预充满。

### 3.3 core/concurrency — SegmentedConcurrency
`tryAcquire(st,cfg)`（`SegmentedConcurrency.java:17`）：TLR probe 路由段，sum≥limit 阻断，否则段+1 返回 bidx；`release(st,bidx)` 同段-1。近似并发（轻微过冲换无锁）。

### 3.4 core/system — SystemOverload
`maybeShed()`（`SystemOverload.java:25`）：volatile SHED_PERMILLE 单读 + 概率丢弃。`onCpuSample` 分级（60/80/90→200/500/800‰）+ 迟滞。低频 daemon 探针（`startProbe`，getCpuLoad，1s）。

## 4. 热更新 / 响应式 / 可观测

### 4.1 core/reload — ConfigSwapper
`swap(int, ResourceConfig)`（`ConfigSwapper.java`）→ `ResourceManager.publishConfig`；STATES 原地不动，下次 acquire 新参数生效。

### 4.2 reactive — CircuitBreakerOperator
`wrap(int, Supplier<Mono<T>>): Mono<T>`（`CircuitBreakerOperator.java:20`）：defer→acquire，<0→`Mono.error(GovernanceException.forToken)`，否则 doOnSuccess/doOnError→release（线程无关）。

### 4.3 observability — CircuitBreakerCollector
`register(CollectorRegistry, int...)`（`CircuitBreakerCollector.java:26`）：Prometheus counter（pass/block，单调）+ gauge（EWMA ppm/1e6），只读 `ResourceState`。

## 5. 模块依赖关系
```
reactive ──> core（FlatExecutionEngine/GovernanceException）
observability ──> core（ResourceState 只读）
benchmarks ──> core（JMH）
core 内：FlatExecutionEngine ──> 四能力 + ResourceManager + TokenCodec + ClockSource
         ConfigSwapper ──> ResourceManager
         ResourceManager ──> LazyTokenBucket（seed）
```

## 6. 已知技术债 / 待办
- `@Contended` 填充未落地（design §6.1 提及）。
- 并发槽位丢失 release 无自愈（依赖 try/finally 契约）。
- lastUpdateMs 24 位（≈4.66h）长空闲轻度失真；version 6 位 64 次热更回绕——均记为已知局限。
