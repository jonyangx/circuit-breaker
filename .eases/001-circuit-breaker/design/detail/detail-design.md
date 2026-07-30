# 详细设计：Circuit Breaker（纳秒级无锁流量治理组件）

**特性分支**：`001-circuit-breaker` | **日期**：2026-07-30 | **关联架构**：`architecture/arch-design.md`
**输入**：`arch-design.md`、`docs/brd/design.md` §3/§4、`artifacts/data-model.md`、用例 `usecases/`

## 1. 模块级设计与职责划分

### 1.1 core 共享内核

| 类 | 职责 | 关键方法 | 关联 UC/BR |
|----|------|----------|-----------|
| `TokenCodec` | 64 位 token 编解码（无分支/无对象） | `encode(time,ver,bidx,mask)`、`decodeTime/Version/Bucket/Mask`、`rtMs(now,token)` | UC-002/003 BR-003 |
| `ClockSource` | 单调相对时钟 | `nowRelMs()` = nanoTime/1M − START | 全部 BR-006 |
| `BlockCode` | 阻断码常量 | `SYSTEM_OVERLOAD=-1` 等 | UC-002 BR-004 |
| `ResourceConfig` | 不可变参数（record） | 字段 mask/ratePerMs/capacity/errThresholdPpm/minCalls/openMillis/ewmaTauMs/concurrencyLimit/version | UC-001 BR-002/050 |
| `ResourceState` | 聚合根（跨版本稳定） | AtomicLong bucketState/breakerState/ewmaState；AtomicInteger[] concurrency；LongAdder pass/block | UC-001 BR-002/051 |
| `ResourceManager` | 注册 + 寻址 | `register(name,policy):int`；`CONFIGS[]`/`STATES[]` volatile 数组 | UC-001 BR-001 |
| `FlatExecutionEngine` | 公共入口 + bitmask 分派 | `tryAcquire(rid):long`、`release(rid,token,ok)` | UC-002/003 BR-005 |
| `PolicyBuilder` | 构建策略 | `enableRateLimit/enableCircuitBreaker/enableConcurrency/min.../build()` | UC-001 |

### 1.2 core 四能力（包级，经引擎分派）

| 类 | 算法核心 | 关联 UC/BR |
|----|----------|-----------|
| `ratelimit.LazyTokenBucket` | 高42位Time\|低22位Tokens；惰性补令牌；仅完整令牌才推进 Time_last | UC-004 BR-010..013 |
| `breaker.EwmaAlpha` | α 分段近似（u≤1/128 α≈u / LUT512+插值 / u≥8 α=1），无 Math.exp | UC-005 BR-021 |
| `breaker.EwmaCircuitBreaker` | ewmaState[gen:4\|last:24\|count:16\|ppm:20]、breakerState[state:2\|gen:4\|end:58]、transition gen+1、代际重播种 | UC-005 BR-020/022/023/024/025 |
| `concurrency.SegmentedConcurrency` | AtomicInteger[SEG]，TLR probe 路由，bucketIdx 入 token | UC-006 BR-030/031/032 |
| `system.SystemOverload` | volatile SHED_PERMILLE，分级 + 迟滞，低频 CPU 探针 | UC-007 BR-040/041/042 |
| `reload.ConfigSwapper` | `CONFIGS.set(rid,newConfig)`，version+1，STATES 不动 | UC-008 BR-050/051/052 |

### 1.3 reactive / observability / benchmarks
- `reactive.CircuitBreakerOperator`：包裹 `Mono<T>`，`defer`→acquire，`doOnSuccess/doOnError`→release（BR-060/061）。
- `observability.CircuitBreakerCollector`：注册 Prometheus `Counter`（pass/block，只读 sum）+ `Gauge`（ppm/1e6）（BR-070/071/072）。
- `benchmarks.AcquireReleaseBenchmark`：JMH `tryAcquire`/`release` 吞吐 + `-gc` 分配计数（SC-001/002）。

## 2. 数据结构与类型定义（位布局，权威契约）

### 2.1 Token（64 位）
```
[63 sign=0][62..22 time:41][21..16 version:6][15..12 bucket:4][11..0 mask:12]
MASK=0xFFF(12) BUCKET=0xF(4) VERSION=0x3F(6) TIME=(1L<<41)-1
SHIFT: MASK=0 BUCKET=12 VERSION=16 TIME=22
encode: ((time&TIME)<<22)|((ver&0x3F)<<16)|((bidx&0xF)<<12)|(mask&0xFFF)
rtMs = (now − decodeTime(token)) & TIME_MASK   // 模减法抗截断
```

### 2.2 bucketState（令牌桶，AtomicLong）
```
高42位 Time_last（单调 ms，≈139年） | 低22位 Tokens（≈400万 QPS 上限）
unpack: tLast = cur>>>22; tok = cur & ((1<<22)-1)
```

### 2.3 ewmaState（AtomicLong，自包含 Δt 与代际）
```
[60..63 gen:4][36..59 lastUpdateMs:24][20..35 count:16(饱和65535)][0..19 ppm:20]
dt = (now − lastUpdateMs) & 0xFFFFFF   // 24位模减，>4.66h 时 u≥8 已饱和
```

### 2.4 breakerState（AtomicLong，携带代际）
```
[62..63 state:2][58..61 gen:4][0..57 endTimeMs:58]
state: 00 CLOSED / 01 OPEN / 10 HALF_OPEN
```

### 2.5 类型选型
- 资源寻址：`volatile ResourceConfig[] CONFIGS`（RCU 整体替换）+ `final ResourceState[] STATES`。
- 并发段：`AtomicInteger[] concurrency`（SEG=16，`@Contended` 可选）；观测：`LongAdder pass/block`。

## 3. 接口实现细节

### 3.1 FlatExecutionEngine.tryAcquire（UC-002）
1. 读 `SHED_PERMILLE`（volatile 单读）；>0 且 `ThreadLocalRandom.nextInt(1000)<shed` → return -1。
2. `cfg=CONFIGS[rid]`、`st=STATES[rid]`、`now=ClockSource.nowRelMs()`。
3. 按 `cfg.mask` 位与：0x01→breaker.tryAcquire、0x02→bucket.tryAcquire、0x04→concurrency.tryAcquire(返 bidx)。任一失败 return 对应负码。
4. 全过：`return TokenCodec.encode(now, cfg.version, bidx, cfg.mask)`。
- **零分配保证**：全程仅 long/int 局部量，无 `new`。

### 3.2 FlatExecutionEngine.release（UC-003）
1. 解码 `bidx/ver/mask`。
2. mask&0x04 → `concurrency[rid][bidx].decrementAndGet()`（线程无关回滚）。
3. mask&0x01 → `breaker.release(st, now, ok, cfg, ver==cfg.version)`。
4. 放行→`passCount.increment()`，否则 `blockCount.increment()`（release 末尾异步递增）。

### 3.3 EwmaCircuitBreaker（UC-005）
- `tryAcquire(st,now)`：CLOSED→放行；OPEN→now≥endTime 则 CAS OPEN→HALF_OPEN(gen+1) 唯一成功放行探路，否则阻断；HALF_OPEN→门闩内放行单探路。
- `release(st,now,ok,cfg,verMatch)`：HALF_OPEN→transition(ok?CLOSED:OPEN)；CLOSED→`updateEwma`（verMatch=false 时降权/跳过），同代且 count≥minCalls 且 ppm≥thr →transition OPEN。
- `transition(st,from,to,end)`：CAS breakerState，gen=(gen+1)&0xF。
- `updateEwma(st,now,xPpm)`：读 gNow=gen(breakerState)；CAS ewmaState：代际不匹配→重播种(count=1,ppm=x)，否则 `dt→alpha(dt,tau)→applyDecay`。

### 3.4 EwmaAlpha.alpha（UC-005，BR-021）
- `u=dt/tau`：u≤1/128→`return u`；u≥8→`return 1`；否则 LUT(idx)+线性插值。静态初始化 `EXP_LUT[513]`。

### 3.5 LazyTokenBucket（UC-004，BR-011/013）
- CAS 循环：`add=(now−tLast)*ratePerMs`；`nTok=min(cap,tok+add)`；若 `nTok<1` 不推进 tLast（仅回写 tok）return -3；否则 `nTok-=1`，next=`(now<<22)|(nTok)`，CAS。

### 3.6 SegmentedConcurrency（UC-006）
- acquire：`bidx=ThreadLocalRandom.current().nextInt() & (SEG−1)`；若 `sum>=limit` return -4；否则 `concurrency[bidx].incrementAndGet()`，return bidx。
- release：`concurrency[bidx].decrementAndGet()`。

### 3.7 SystemOverload（UC-007）
- 低频线程（1s）采 CPU，按分级阈值（含迟滞）写 `volatile int SHED_PERMILLE`。

### 3.8 ConfigSwapper（UC-008）
- `swap(rid,newCfg)`：`CONFIGS[rid]=newCfg(version=old.version+1)`；STATES 原地不动。

## 4. 错误处理与异常分类

| 场景 | 处理 | 关联 |
|------|------|------|
| 阻断（限流/熔断/并发/过载） | 返回负 `long` 阻断码，**不抛异常**（避免栈分配） | BR-004 |
| 非法 resourceId（越界/未注册） | 抛 `IllegalArgumentException`（非热路径） | UC-001 |
| acquire 后业务未 release | 资源泄漏（并发计数不归零）——文档警示 + try/finally 约定 | UC-003 NFR |
| CAS 自旋长期失败 | 理论上不发生（无阻塞）；不设退避（纳秒预算内必收敛） | 不变量3 |

## 5. 事务与一致性细节

- **无锁原子性**：每状态字段单 CAS 原子；breaker/ewma 跨字段一致性由代际标签惰性对齐（BR-024），无复合原子操作。
- **ABA 防护**：gen 4 位（16 代），迁移回同代需亚微秒窗口内迁移满 16 次，不可能（design §4.3.3）。
- **热更新在途**：version 校验三态（一致/不一致→并发照常回滚、EWMA 降权）（BR-052）。

## 6. 性能要点与资源使用

- **零分配**：所有热路径方法仅原始类型局部变量；token 是 long。
- **无 Math.exp**：EwmaAlpha 查表 + 一阶近似。
- **缓存行**：bucketState/breakerState 建议 `@Contended`（`-XX:-RestrictContended`）。
- **资源**：每资源 3 个 AtomicLong + SEG 个 AtomicInteger + 2 LongAdder ≈ 数十字节，无 LeapArray。

## 7. 配置与参数

| 参数 | 来源 | 默认/范围 | 关联 BR |
|------|------|-----------|---------|
| mask | PolicyBuilder | 0x01/0x02/0x04 组合 | BR-005 |
| ratePerMs / capacity | enableRateLimit(qps) | 由 qps 推导 | BR-011 |
| errThresholdPpm | enableCircuitBreaker(f) | f×1e6 | BR-022 |
| minCalls | minimumCalls(n) | 数十~数百 | BR-023 |
| openMillis | — | 秒级 | BR-025 |
| ewmaTauMs | ewmaHalfLife(ms) | 半衰期 | BR-020 |
| concurrencyLimit | enableConcurrency(n) | — | BR-030 |
| SEG | 编译期常量 | 16 | BR-030 |
| resourceId 上限 | — | 1024 | BR-001 |

## 8. 可观测性落地

- pass/block → Prometheus `Counter`（type=monotonic，scraper 算差值，禁 reset BR-071）。
- EWMA → `Gauge`（ppm/1e6，BR-072）。
- 热路径禁日志；注册/热更新可结构化日志。

## 9. 测试设计映射

| 测试类型 | 覆盖 | 关联 |
|----------|------|------|
| 单元 | TokenCodec 位布局/模减、EwmaAlpha 误差、令牌桶补令牌/抹零、并发回滚 | UC-002/004/005/006 BR-003/011/013/021 |
| 单元（状态机） | 三态迁移闭环、代际重播种（HALF_OPEN→CLOSED 不二次跳闸） | UC-005 BR-024/025 |
| 集成 | 跨线程 release 求和归零、热更新在途 release 零漂移 | UC-003/008 SC-004 |
| 集成（reactive） | Mono 跨线程 acquire/release | UC-009 BR-060 |
| 性能（JMH） | acquire P50<100ns、release<50ns、0 分配 | SC-001/002 |

## 10. 依赖清单与版本策略

| 模块 | 依赖 | 版本策略 |
|------|------|----------|
| core | JDK 21（无三方） | 跟随 LTS |
| reactive | io.projectreactor:reactor-core | libs.versions.toml |
| observability | io.prometheus:simpleclient | libs.versions.toml |
| benchmarks | org.openjdk.jmh:jmh-core | libs.versions.toml |
| test | org.junit.jupiter:junit-jupiter、org.assertj:assertj-core、org.jacoco | libs.versions.toml |
