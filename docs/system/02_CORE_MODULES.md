# 02 核心模块（Core Modules）

> 来源：`src/main/java/dev/circuitbreaker/**` · 证据 `文件:符号`

## 1. 模块总览

| 模块（包） | 职责 | 关键类 |
|---|---|---|
| `core`（共享内核） | 资源寻址、token、时钟、引擎、配置、异常 | `FlatExecutionEngine`、`ResourceManager`、`TokenCodec`、`ResourceConfig/State`、`PolicyBuilder`、`PolicySpec`、`GovernanceException`、`ClockSource`、`BlockCode` |
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
- `register(ResourceConfig): int`（`ResourceManager.java:20`，synchronized）→ 分配 resourceId（0..1023），建 STATES[id]，seed 令牌桶，发布 CONFIGS[id]。无 `name` 参数（N2：原 `name` 从未被使用，删除以避免无去重机制下的控制面 map 单调增长）。
- `publishConfig(int, ResourceConfig)`（`:51`）：RCU 受控入口（ConfigSwapper 用）。
- `CONFIGS`: `AtomicReferenceArray<ResourceConfig>`（安全发布）；`STATES`: `final ResourceState[]`（set-once，跨版本稳定）。

### 2.3 TokenCodec（64 位 token）
位布局 `[sign:1][time:37][version:10][bucketIdx:4][mask:12]`（version 10 位 / time 37 位——C2 扩位，回绕点 1024 次热更）；`encode/decode*`（`TokenCodec.java`）；RT 模减法 `rtMs`。无分支、无对象。

### 2.4 ResourceConfig（不可变实体）/ ResourceState（聚合根）
- `ResourceConfig`: 9 个 `public final` 字段（mask/qps/capacity/errThresholdPpm/minCalls/openMillis/ewmaTauMs/concurrencyLimit/version）。
- `ResourceState`: 聚合根，`public final` AtomicLong（bucketState/breakerState/ewmaState）+ AtomicInteger[16] concurrency + LongAdder pass/block；`ewmaErrorRatePpm()` 只读访问器。详见 `04_DATA_MODEL.md`。

### 2.5 PolicyBuilder（策略构建 + 校验）
链式 `enableRateLimit/enableCircuitBreaker/enableConcurrency/minimumCalls/ewmaHalfLife/openMillis` → `build()`（`PolicyBuilder.java:44`）做单字段入参校验（errThreshold∈(0,1]、τ>0、qps∈(0,4_194_303]、minCalls>0、concurrency>0、openMillis>0）。可选 `sla(SlaFacts)`（`:42`）附 SLA 事实，`build()` 末尾经 `PolicySpec`（§2.6）做跨参数 SLA 校验（**opt-in**，不附则跳过）。

### 2.6 PolicySpec（离线跨参数 SLA 校验）
`check(cfg, SlaFacts): List<Finding>`（`PolicySpec.java:86`）/ `isValid(cfg, sla): boolean`（`:103`）：对照 SLA 事实校验参数间不变量 S1–S5（余量 / Little's Law / 样本可攒齐 / 跳闸余量 / 冷启动地板）。**非热路径**——仅注册/热更新期经 `PolicyBuilder.sla()` opt-in 触发，ERROR 抛 `IllegalArgumentException`、WARN 不阻断。SLA→参数换算与不变量详见 `05_CONFIG_MANAGEMENT.md` §5/§6。

### 2.7 GovernanceException（块码→类型化异常）
`forToken(long)` 返回 / `throwFor(long)` 抛出（`GovernanceException.java`）；base + 4 子类（RateLimited/CircuitOpen/ConcurrencyLimited/SystemOverloaded），均带 serialVersionUID。

## 3. 四能力（经 bitmask 分派）

> **算法的第一性原理推导、对抗性边界、低 TPS / 启动异常免疫分析，详见 `07_ALGORITHM_DEEP_DIVE.md`。** 本节为模块职责与签名速查。

### 3.1 core/breaker — EwmaCircuitBreaker
- `tryAcquire(st, cfg, nowMs)`（`EwmaCircuitBreaker.java:31`）：CLOSED 放行；OPEN 到期→HALF_OPEN 单探路；HALF_OPEN 探路截止→惰性回退 OPEN **自愈**。
- `release(st, nowMs, ok, cfg, verMatch)`（`:55`）：HALF_OPEN→CLOSED/OPEN；CLOSED→updateEwma+跳闸判定；`verMatch=false`（在途 release 跨版本）则**跳过 EWMA 更新**（`FlatExecutionEngine.java:69`）。
- `transition()`（`:81`）是**唯一**改 generation 的入口；`updateEwma`（`:95`）代际不匹配则丢弃陈旧累积、用当前样本**重播种**（等价"进 CLOSED 清零"，无显式清零 CAS）。
- `EwmaAlpha.alpha(dt,τ)` 分段近似（无 Math.exp）；退化态：`dt≤0`→`α=0`，`τ≤0`→`α=1`。
- **跳闸三条件全满足才熔断**（`:68-75`）：`ewGen==genNow ∧ count≥minCalls ∧ ppm≥errThresholdPpm`。`minCalls` 是冷启动保护——启动初期 100% 失败也不跳闸（详见 07 §5.2）。

### 3.2 core/ratelimit — LazyTokenBucket
`tryAcquire(st, cfg, nowMs)`（`LazyTokenBucket.java:29`）：高42位Time|低22位Tokens，惰性补 `add=(now-tLast)*qps/1000`，`nTok=min(capacity, tok+add, TOKEN_MASK)` 双层截断防溢出，`nTok<1` 不推进 tLast（**抹零对策 BR-013**——低 QPS 不饿死）。`seed()`（`:24`）注册时预充满且 `min(capacity, TOKEN_MASK)`。**不分段**（持有全局 QPS 上限不变量）。

### 3.3 core/concurrency — SegmentedConcurrency
`tryAcquire(st,cfg)`（`SegmentedConcurrency.java:17`）：TLR probe 路由段（非 threadId，虚拟线程安全），`sum≥limit` 阻断，否则段+1 返回 bidx；`release(st,bidx)` 据 token 解出的 bidx 回同段-1。近似并发（轻微过冲换无锁；过冲是已知可接受权衡，详见 07 §3.1）。

### 3.4 core/system — SystemOverload
`maybeShed()`（`SystemOverload.java:25`）：volatile `SHED_PERMILLE` 单读 + 概率丢弃；`SHED_PERMILLE=0`（默认/探针未启）恒返回 false。`onCpuSample`（`:31`）分级（60/80/90→200/500/800‰）+ 迟滞（进入阈值−退出阈值≈10%）。低频 daemon 探针（`startProbe`，getCpuLoad，1s），`catch(Throwable)` 探针异常永不崩 JVM。

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
         PolicyBuilder ──> PolicySpec（opt-in 校验）
```

## 6. 已知技术债 / 待办
- 三个热点 `AtomicLong`（bucketState/breakerState/ewmaState）已 `@Contended` 填充。**注意：用户类的 `@Contended` 默认被 JVM 忽略**，生产 JVM 必须启动带 `-XX:-RestrictContended`（外加 `--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED`）；否则填充失效、伪共享照旧。构建已为 test/jmh 注入，`ContendedPaddingGuardTest` 守卫。`concurrency[16]` 未填充（已 16 路分段，单槽竞争低，影响有限）。
- **并发槽位丢失 release 无自愈【硬契约】**：丢失 release 永久泄漏一个并发槽位（熔断侧有自愈，并发侧没有）。业务必须 `try/finally` 成对释放，详见 `03_API_INTERFACE.md` API-003。
- `ewmaState.lastUpdateMs` 20 位（≈17.5min）长空闲轻度失真（α 在 8τ=40s 已饱和，回绕误差被自平滑吸收）；token `version` 10 位（1024 次热更回绕）；`generation` 8 位（256 代回绕）——均记为已知局限。
- 算法深度分析与对抗性边界清单见 `07_ALGORITHM_DEEP_DIVE.md`。
