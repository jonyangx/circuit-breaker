# 07 算法深度剖析（Algorithm Deep-Dive）

> 本文以**第一性原理（first principles）**推导每个治理算法的核心不变量，并以**对抗性原理（adversarial reasoning）**穷举边界与故障态，论证"为何在极端/低 TPS/启动异常下仍正确"。
> 来源：`docs/brd/design.md` §3.2/§4.2/§4.3/§4.4/§10 + `src/main/java/dev/circuitbreaker/**` · 证据 `文件:符号`。
> 配套测试：`src/test/java/dev/circuitbreaker/core/StartupImmunityTest.java`（启动异常）、
> `src/test/java/dev/circuitbreaker/core/breaker/LowTpsBreakerTest.java` + `core/ratelimit/LowTpsTokenBucketTest.java`（低 TPS）、
> `src/test/java/dev/circuitbreaker/core/breaker/TpsDynamicsBreakerTest.java` + `core/ratelimit/TpsDynamicsTokenBucketTest.java` +
> `core/concurrency/TpsDynamicsConcurrencyTest.java` + `core/system/TpsDynamicsSystemOverloadTest.java` +
> `core/TpsDynamicsEngineTest.java`（TPS 突增/突降/抖动）。

## 0. 阅读导引

- §1 EWMA 熔断器：为什么用时间衰减、ppm 定点、代际防 ABA、自愈。
- §2 惰性令牌桶：为什么惰性、为什么不分段、抹零对策、22 位上限。
- §3 分段并发：为什么近似、为什么用 TLR probe、过冲分析。
- §4 系统过载：为什么分级、为什么迟滞、为什么概率。
- §5 **启动异常免疫**（专项）：为什么启动时的脏时钟/未启探针/空 EWMA 都不影响熔断效果。
- §6 **低 TPS 行为**（专项）：1 req/s 甚至更稀疏的流量下，各算法的数学行为。
- §7 对抗性边界汇总表。
- §8 **TPS 突增/突降/抖动**（专项反思）：对四种算法的逐项影响分析 + 实测验证结论。
- §10 **多服务隔离与雪崩预防**（专项反思）：跨资源状态隔离、唯一全局耦合点、级联雪崩概率分析。
- §11 与其它文档的关系。

---

## 1. EWMA 熔断器（`core/breaker/EwmaCircuitBreaker`）

### 1.1 第一性原理：为什么是"时间衰减"而非"滑动窗口"

Sentinel/Hystrix 用滑动窗口（`LeapArray`）统计错误率，本质是"**时间维度上均等的离散桶**"。这带来两个根本代价：

1. **内存**：N 个桶 × `MetricBucket`，每桶多个 long/对象 → 数十字节起。
2. **后台线程**：桶需要按时滚动，必须有定时器把当前桶推到历史桶（否则窗口不前进）。

本组件的两个不变量（**零堆分配**、**治理侧无定时线程**）直接否定了滑动窗口。**时间衰减 EWMA** 用一个 `long`（`ewmaState`）压缩"错误率 + 样本计数 + 上次更新时间 + 代际"，把"窗口"退化成"一个指数遗忘的标量"：

```
α = 1 - exp(-Δt/τ)
EWMA_t = EWMA_{t-1} + α·(X_t − EWMA_{t-1})     // X_t ∈ {0(成功), 1(失败)}
```

关键性质（也是为什么它替代滑动窗口是安全的）：

- **时间一致性**：`α` 只依赖 `Δt`，与样本到达频率（TPS）无关。高 QPS 资源与低 QPS 资源用**同一套衰减律**，行为可预测。这是 v1 用 `α≈2/(N+1)`（N=样本数）的最大缺陷——低 QPS 资源 N 增长慢，EWMA 长期陈旧（见 `docs/brd/design.md` §4.3.1）。
- **有界性**：`EWMA_t` 是当前样本与历史值的**凸组合**（系数和为 1），恒 ∈ [0, 1e6] ppm。不会爆炸、不会为负（见 §1.6 证明）。
- **惰性兼容**：`Δt` 来自 `ewmaState` 自带的 `lastUpdateMs`，**无需额外时钟读取**，契合"惰性时间推导"。

### 1.2 为什么错误率用 ppm 定点整数

浮点 `double` 在 CAS 自旋下有两个问题：

1. **非结合性**：`(a+b)+c ≠ a+(b+c)`（浮点），并发线程以不同归约顺序重试 CAS 会产生**非确定性抖动**——同一个逻辑值，不同线程算出不同二进制位，CAS 永远失败或结果漂移。
2. **`floatToIntBits` 抖动**：把 double 装进 long 的位转换不是恒等映射，NaN/非规格化数会放大误差。

ppm 定点（`0..1_000_000`，20 位恰好容纳 `2^20=1_048_576 ≥ 1e6`）让所有运算变成**整数加减乘**，CAS 的"比较-交换"语义严格成立（`EwmaCircuitBreaker.java:116 applyDecay`）。`Math.round` 避免向零截断的系统性偏差（`design.md` §4.3.1.1）。

### 1.3 对抗性：代际（generation）如何同时消灭 ABA 与"僵尸错误率"

**问题**：`breakerState` 与 `ewmaState` 是**两个独立 `AtomicLong`**，无法在一条指令里原子更新两者。`HALF_OPEN→CLOSED` 后若不清 EWMA，旧的高错误率会**立刻再次跳闸**（熔断器永远出不来）；而"读旧值→CAS 写 0"存在 ABA 与并发竞态窗口。

**解法**（`EwmaCircuitBreaker.java:81 transition`、`:95 updateEwma`）：把"两者一致性"转化为"更新时校验代际标签"：

- `transition()` 是**唯一**改 `generation` 的入口，每条迁移边 `gen = (gen+1) & 0xFF`（8 位，循环 256；C3 已由 v1 的 4 位/16 代扩位）。
- `updateEwma()` 先读当前权威代 `gNow = brGen(breakerState)`；若 `ewmaState` 里残留的旧代 ≠ `gNow`，**丢弃陈旧累积、用当前样本重播种**（`count=1, ppm=X_t, last=now, gen=gNow`）——语义等价于"进 CLOSED 清零 EWMA"，但**无需显式清零 CAS**。

**为什么 4 位（16 代）足以防 ABA**：陈旧更新要"误判为同代"，需在"读 gNow → CAS 写入"这段**亚微秒窗口**内 `breakerState` 恰好迁移满 16 次回到同一代际值。真实状态机节奏（每次迁移间隔至少是一次 acquire+release 周期）下不可能发生。即便极端撞上，后果仅是**单个样本被错误归代**，被 EWMA 后续自平滑吸收（见 `design.md` §4.3.3 末段）。

### 1.4 对抗性：丢失探针的自愈（`HALF_OPEN` 不再卡死）

`HALF_OPEN` 态下"仅放行单个探路请求，其余阻断"。但若这个探路的 `release()` 永远不发生（线程被 kill、网络分区、Bug 漏 finally），资源会被**永久卡在 HALF_OPEN**——这是致命的。

**解法**（`EwmaCircuitBreaker.java:42`、`:49-51`）：`OPEN→HALF_OPEN` 时写入的 `endTime` 字段**兼作探路截止**：

```
OPEN, now≥endTime  → CAS 抢占 → HALF_OPEN, endTime = now + openMillis   // endTime 现在是"探路截止"
HALF_OPEN, now≥endTime → transition(HALF_OPEN→OPEN, now+openMillis)     // 探路超时，重新武装 OPEN
```

于是"丢失的探针"最多卡 `openMillis`，之后被下一个 acquire 惰性自愈为 OPEN，再过一个 `openMillis` 重新选出探针。**全程无治理侧定时器**，完全由请求到达驱动（契合不变量 4）。

### 1.5 α 的分段近似：为什么热路径不调 `Math.exp`

单次 `Math.exp` ≈ 20–40ns（HotSpot），与整条治理路径同量级，纳秒预算下不可接受（`EwmaAlpha.java`、`design.md` §4.3.1.1）。令 `u=Δt/τ`，分三段：

| u 区间 | 处理 | 依据/误差 |
|---|---|---|
| `u ≤ 1/128` | `α ≈ u`（一阶泰勒） | 相对误差 `≈u/2 < 0.4%`，**无查表、无分支预测惩罚** |
| `1/128 < u < 8` | LUT(512) + 线性插值 | 凸函数插值绝对误差 `≤ STEP²/8 ≈ 3e-5`（ppm 级） |
| `u ≥ 8` | `α = 1`（完全衰减） | `e^{-8} < 3.4e-4`，视作完全遗忘 |

**退化态**（`EwmaAlpha.java:28-34`）：
- `dtMs ≤ 0`（同毫秒或时钟回退）→ `α=0`，不衰减（同一瞬多个样本不互相稀释，语义正确）。
- `tauMs ≤ 0`（配置退化）→ `α=1`，全衰减（`PolicyBuilder.build` 已拦截 `ewmaTauMs>0`，此分支仅作防御）。

**为什么插值误差 ppm 级即可接受**：错误率本就以 ppm 存储，分辨率 `1e-6`。α 的 `3e-5` 绝对误差经 `α·(X_t−EWMA)` 传播，单步最多贡献约 30 ppm，且 EWMA 自平滑会吸收、不累积。

### 1.6 证明草图：ppm 凸组合恒 ∈ [0, 1e6]

```
applyDecay(p, x, α) = p + round(α·(x − p))
```

- 若 `x ≥ p`：`p + round(α·(x−p)) ∈ [p, x]`（因 `0≤α≤1`）→ ≤ `x ≤ 1e6`。
- 若 `x < p`：`p + round(α·(x−p)) ∈ [x, p]` → ≥ `x ≥ 0`。

故无论 `α` 取何值（含近似误差），结果恒落在 `[0, 1e6]`。**EWMA 数学上不可能爆炸或变负**。`count` 用 `Math.min(0xFFFF, ...)` 饱和，永不溢出 16 位。

---

## 2. 惰性令牌桶（`core/ratelimit/LazyTokenBucket`）

### 2.1 第一性原理：为什么"惰性"能消灭定时器

传统漏桶/令牌桶需要一个**后台线程**周期性向桶里"滴"令牌。本组件把"补充"推迟到每次 `tryAcquire`，用时间差**当场推导**该补多少：

```
add = (now − tLast) × qps / 1000        // 整数算术，ms×(token/s)/1000 = token
nTok = min(capacity, tok + add)
```

无定时器（不变量 4）、无分配（不变量 2）、单 `AtomicLong` CAS（不变量 3）。**惰性的代价是"补多少只在被查询时才确定"**，但对限流语义无害——桶只在 acquire 时被读，从未被异步读。

### 2.2 对抗性：抹零对策（BR-013，低 TPS 的生死线）

**问题**：`add = (now−tLast)·qps/1000` 是**整数除法**。若 `qps=1`（0.001/ms），`Δt=500ms` → `add = 500·1/1000 = 0`（被抹零）。若此时 `tLast` 被推进到 `now`，**那 500ms 的累积就永久丢失**——桶永远生成不出令牌，限流器在低 QPS 下饿死。

**解法**（`LazyTokenBucket.java:41-44`）：**只有当 `nTok ≥ 1`（确实生成了完整令牌）时才把 `tLast` 推进到 `now`；否则 `return false` 且 `tLast` 原地不动**。于是未达整数门槛的时间差**持续累积**，直到跨过 `1000/qps` 毫秒门槛才一次性兑换。

```
tryAcquire(now=500):  add=0, nTok=0 <1 → 不推进 tLast，返回 false（但 tLast 仍是 0）
tryAcquire(now=999):  add=0, nTok=0 <1 → 不推进 tLast，返回 false
tryAcquire(now=1000): add=1, nTok=1 ≥1 → 推进 tLast=1000, 返回 true ✓
```

这是**低 TPS 正确性的核心**，详见 §6.1 测试用例。

### 2.3 第一性原理：为什么令牌桶**绝不分段**（BR-012）

令牌桶持有**全局不变量：QPS 上限**。分段（stripe）会把不变量打碎：

- 若各子桶都持满 `capacity` → 总放行放大 N 倍，**限流失效**。
- 若均摊 `rate/N` → 某桶耗尽而其他桶有余令牌时**误杀**正常请求。

故令牌桶**必须单 `AtomicLong`**（`LazyTokenBucket.java:30`）。超高 QPS 下单点 CAS 重试成本，远小于误杀/放大的业务代价（`design.md` §4.2）。**分段只用于可交换求和量**（观测计数、近似并发），见 §3。

### 2.4 对抗性：22 位上限与防溢出（commit `932aaab`）

令牌字段仅 22 位（`≤4_194_303`）。若 `capacity` 或 `qps` 超过，`tok+add` 会溢出 22 位、**污染相邻的 Time 字段**（42 位时间戳），导致后续所有 `Δt` 计算错误、限流彻底失灵。

**双层防御**：
1. **`seed()` 截断**（`LazyTokenBucket.java:25`）：`Math.min(capacity, TOKEN_MASK)`。
2. **热路径再截断**（`:40`）：`nTok = min(min(capacity, tok+add), TOKEN_MASK)`——即使 `capacity` 合法但 `tok+add` 因长时间累积超过 `TOKEN_MASK`，也封顶，不污染 Time。

`PolicyBuilder.build`（`:41`）额外校验 `qps ≤ 4_194_303`，在构造期就拒绝超限配置。

---

## 3. 分段并发（`core/concurrency/SegmentedConcurrency`）

### 3.1 第一性原理：为什么近似而非精确

精确并发计数（单 `AtomicInteger`）在超高并发下是**单点 CAS 热点**。并发数是**可交换求和量**（`sum(seg_i)` 与分段顺序无关），天然适合分段降热点。

但并发数**没有"全局不变量"**（不像令牌桶的 QPS 上限是硬约束）——它只是一个"当前在途请求数"的估计。**允许轻微过冲**（overshoot）换取无锁，业务上完全可接受：并发限制本就是软保护，超 1~2 个并发远不如限流失真致命。

### 3.2 对抗性：为什么用 `ThreadLocalRandom` probe 而非 `threadId`

Java 21 虚拟线程的 `threadId` 唯一且**短命**（数百万个、用完即弃）。`threadId & (SEG-1)` 既无热点局部性、数量也无界，**stripe 初衷（降低单点竞争）完全失效**。

`ThreadLocalRandom.current().nextInt(SEG)`（`SegmentedConcurrency.java:18`）是 LongAdder 同款 probe：每个线程本地无竞争地选段，16 段下热点被充分摊薄。

### 3.3 对抗性：过冲窗口与 release 回段

`tryAcquire` 先**求和全部 16 段**判断 `sum ≥ limit`，再对路由段 `+1`（`:19-27`）。这两步之间无锁，极端并发下可能多个线程同时通过判断、各自 `+1`，导致**瞬时过冲**（实际并发 > limit）。这是**已知、可接受的权衡**（`design.md` §4.4、`:11-12` 注释）。

`bucketIdx` 写入 token（`FlatExecutionEngine.java:47`、`TokenCodec`），`release` 据 token 解出的 `bucketIdx` **回到同一段 `-1`**（`SegmentedConcurrency.java:30`）——所以即使 acquire 跨线程（Reactor 切换），并发计数也**不漂移**（不变量 6 的直接收益）。**已知技术债**：若 `release` 因业务漏 `try/finally` 而丢失，该段计数永久偏高（`02_CORE_MODULES.md` §6）——但不影响其他段，且熔断/限流独立运作。

---

## 4. 系统过载（`core/system/SystemOverload`）

### 4.1 第一性原理：为什么分级 + 概率，而非全有/全无

v1 用单 `boolean` 全量拒绝，过载边缘会产生**自激振荡**：全放行→CPU 飙高→全拒绝→CPU 暴跌→全放行……循环。**分级概率丢弃**（`SHED_PERMILLE`，0–1000‰）在边缘平滑降载：60%→丢 20%、80%→丢 50%、90%→丢 80%（`SystemOverload.java:32-34`）。每次 acquire 只丢"概率比例"的请求，其余正常进入资源策略——既降压又不断流。

### 4.2 对抗性：迟滞（hysteresis）防抖动

进入某档的阈值**高于**退出该档的阈值（`onCpuSample` + `exitThreshold`，`:31-54`）：

| 档位（‰） | 进入阈值（CPU%） | 退出阈值（CPU%） |
|---|---|---|
| 200 | ≥60 | <50 |
| 500 | ≥80 | <70 |
| 800 | ≥90 | <80 |

CPU 在 75% 附近抖动时，不会在 200‰↔500‰ 之间反复横跳——已进入 500‰ 档需跌到 <70% 才退出。**迟滞窗口 = 进入阈值 − 退出阈值 ≈ 10%**，足以吸收秒级抖动。

### 4.3 第一性原理：探针为何不在热路径

热路径 `maybeShed()`（`:25`）只做**一次 volatile 读**（`SHED_PERMILLE`）+ 一次 `ThreadLocalRandom` 比较——O(1)、无分配、无系统调用。真正的 CPU 采样（`getCpuLoad`，JMX 调用，微秒级）由**低频 daemon 线程**每秒一次完成（`:78 probeLoop`），结果写入 volatile 供热路径读。**采集与判定解耦**，采集慢/失败绝不拖累请求路径（`:93 catch(Throwable)`——探针异常永不崩溃被守护 JVM）。

---

## 5. 启动异常免疫（专项）

> **核心命题**：进程刚启动时（类加载、JIT 预热、配置未就绪、探针未启、时钟刚归零）出现的任何"脏状态"，都**不会**污染后续真实熔断/限流判定。下面逐项论证，对应测试见 `StartupImmunityTest.java`。

### 5.1 时钟刚归零——`ClockSource.START` 不影响相对判定

`ClockSource.START = nanoTime/1e6` 在类加载时定一次（`ClockSource.java:8`）。所有治理判定用**相对值** `nowRelMs = nanoTime/1e6 − START`（`:13`），且：

- 令牌桶、EWMA 只用 **Δt（两次读数之差）**，与 START 的绝对值无关。START 偏大偏小，Δt 不变。
- RT 用模减法 `(now − decodeTime) & TIME_MASK`（`TokenCodec.java:45`），只要单次 RT < 2^41 ms（≈69 年）即正确，与进程运行多久、START 何时定均无关。

**结论**：启动时 `nanoTime` 的任何初值，对治理判定零影响。

### 5.2 EWMA 初始全零——"起步偏健康"是设计意图，不是缺陷

`ewmaState` 初始为 0（ppm=0=健康、count=0、gen=0）。设计文档明确（`design.md` §4.3.2）：EWMA 从 0 起，首次失败仅得 `α·1e6`（小 α 下很小），**不会飙到 100%**。真正的冷启动保护是 **`count ≥ minCalls` 门槛**（`:72`）——样本不足前**禁止跳闸**。所以：

- 启动初期即使 100% 失败，只要 `count < minCalls`，**绝不跳闸**（见 `StartupImmunityTest.startupFewFailuresNeverTrip`）。
- `minCalls` 是"信任建立期"：攒够样本才允许基于错误率做判定，避免冷启动误杀。

### 5.3 令牌桶种子——seed 后立即可用，无暖机

`ResourceManager.register`（`:26`）对 `mask & 0x02` 的资源调 `LazyTokenBucket.seed(st, capacity)`，预充满到满容量。**注册即可放行突发**，无需等待"第一个令牌生成"。seed 用 `Math.min(capacity, TOKEN_MASK)` 截断（`:25`），即使配置 capacity 超限也不溢出。

### 5.4 系统过载探针未启——`SHED_PERMILLE` 默认 0，永不过载拦截

`SHED_PERMILLE` 是 `volatile int = 0`（`SystemOverload.java:16`）。`maybeShed()`（`:25-28`）当 `shed=0` 时**直接返回 false**，不进入概率分支。所以：

- 即使从未调用 `startProbe()`（探针未启），`maybeShed()` 恒 false，**系统过载模块对启动完全透明**（见 `StartupImmunityTest.overloadProbeAbsentDoesNotBlock`）。
- `startProbe` 是幂等的（`probeRunning` CAS，`:64`），重复调用安全。

### 5.5 配置校验前置——非法配置在构造期即被拒，进不了运行时

`PolicyBuilder.build`（`:34-58`）在**构造 ResourceConfig 之前**校验所有边界：`openMillis>0`、`qps∈(0,4_194_303]`、`errThreshold∈(0,1]`、`ewmaTauMs>0`、`minCalls>0`、`concurrency>0`。非法配置抛 `IllegalArgumentException`，**永远到不了热路径**。运行时无需再校验，热路径保持纯净。

### 5.6 版本/代际防线——脏 release 进不了错误代

release 时 `versionMatch = (decodeVersion(token) == (cfg.version & VERSION_MASK))`（`FlatExecutionEngine.java:69`）。热更新发生在 acquire 与 release 之间时，`versionMatch=false` → `updateEwma` **被跳过**（`EwmaCircuitBreaker.java:63-65`），旧阈值语义不污染新配置。代际（§1.3）则在状态迁移时惰性作废旧 EWMA。**任何"跨配置/跨代"的脏 release 都被隔离**。

---

## 6. 低 TPS 行为（专项）

> **核心命题**：算法在 1 req/s 甚至更稀疏的流量下数学行为正确。对应测试见 `LowTpsBreakerTest` / `LowTpsTokenBucketTest`。

### 6.1 令牌桶：qps=1，亚秒级不生成、跨秒才生成（抹零对策实证）

`qps=1` → `add = (now−tLast)·1/1000`。整数除法：

| now(ms) | add | nTok | 行为 |
|---|---|---|---|
| 500 | 0 | 0 | 不生成，tLast 不推进（累积 500ms） |
| 999 | 0 | 0 | 不生成，tLast 不推进（累积 999ms） |
| 1000 | 1 | 1 | 生成，tLast→1000 |
| 2000 | 1 | 1 | 再生成（若已消耗） |

**关键不变量**：长空闲后 tLast 不动，时间差累积；一旦跨过 `1000/qps` 门槛一次性兑换。**低 QPS 不会饿死**（见 `LowTpsAlgorithmTest.tokenBucketQpsOneSubSecondNoToken`、`tokenBucketQpsOneCrossSecondOneToken`）。

更极端：`qps=1, capacity=1`，长时间空闲（如 10s）后，`add=10` 但被 `min(capacity=1, ...)` 截断为 1——**突发不超过 capacity**，令牌桶语义保持。

### 6.2 EWMA：稀疏采样下 α→1，EWMA 退化为"最近样本"

低 TPS 意味着两次 release 间隔 `Δt` 很大。若 `Δt/τ ≥ 8`（如 τ=1s、间隔 8s+），`α=1`（`EwmaAlpha.java:39`），`EWMA_t = EWMA_{t-1} + 1·(X_t − EWMA_{t-1}) = X_t`。**EWMA 完全遗忘历史，等于当前样本**。这是**语义正确**的：极稀疏流量下，历史错误率早已无意义，当前样本才是唯一可信信号。

但 `count ≥ minCalls` 门槛仍在（`:72`）——即便单个样本 ppm 飙到 1e6，样本数不足也不跳闸。**低 TPS 下熔断反应变慢是设计权衡**（攒样本需要时间），不是缺陷。

### 6.3 EWMA：τ 间隔采样——错误率可解析地爬升到阈值

测试与生产场景中，让失败按 τ 间隔到达，可解析推导 ppm 爬升曲线。以 `τ=1000ms, threshold=500_000ppm, minCalls=5`（`EwmaCircuitBreakerTest.trip` 的配置）为例：每次失败 `α = 1−e^{−1} ≈ 0.632`，ppm 依 `p_{n} = p_{n-1} + 0.632·(1e6 − p_{n-1})` 爬升，5 次后远超 500_000ppm → 跳闸。**稀疏（1 req/τ）流量下熔断仍能在 minCalls 个样本内触发**（见 `LowTpsBreakerTest.sparseTauSpacedFailuresStillTrip`）。

同样，4 次失败（差 1 次到 minCalls=5）必须不跳闸——`LowTpsBreakerTest.sparseFourFailuresNeverTrips` 已验证。这个边界等价于"冷启动保护"§5.2 在低 TPS 下的延续。

### 6.4 并发：低 TPS 下并发槽近乎恒空，并发限制几乎不触发

低 TPS 下在途请求数趋近 0，`sum(concurrency)` 远低于 limit，`SegmentedConcurrency` 恒放行。**低 TPS 与并发控制正交**——并发控制只在"高并发低延迟"场景才生效。

---

## 7. 对抗性边界汇总表

| 算法 | 对抗场景 | 系统行为 | 保障机制（证据） |
|---|---|---|---|
| EWMA | 跨线程并发 release | CAS 自旋，结果收敛 | `updateEwma` for-loop CAS（`:97`） |
| EWMA | 代际 256 次回绕 | 即便撞上仅单样本误归代，自平滑吸收 | 8 位 gen + 凸组合有界（§1.3/§1.6） |
| EWMA | 丢失探针 | `openMillis` 后惰性自愈为 OPEN | `tryAcquire` HALF_OPEN 分支（`:49`） |
| EWMA | 首次失败 | ppm 仅 `α·1e6`（小），不飙 100% | α 分段 + count 门槛（§1.5/§5.2） |
| EWMA | `dtMs≤0`（时钟回退/同瞬） | α=0，不衰减 | `EwmaAlpha.alpha`（`:29`） |
| 令牌桶 | 低 QPS 抹零 | tLast 不推进，时间差累积 | BR-013（`:41-44`） |
| 令牌桶 | `capacity>2^22` | 双层截断，不污染 Time 字段 | seed + 热路径 min（`:25`/`:40`） |
| 令牌桶 | CAS 竞争 | 自旋重试，无饥饿 | `tryAcquire` for-loop（`:33`） |
| 并发 | 极端竞争过冲 | 瞬时超 limit 几个，可接受 | 无全局不变量（§3.1） |
| 并发 | 跨线程 release | 据 token bucketIdx 回同段 | 自描述 token（不变量 6） |
| 过载 | CPU 边界抖动 | 迟滞防横跳 | 进入/退出阈值差 10%（§4.2） |
| 过载 | 探针异常 | catch(Throwable)，不崩 JVM | `probeLoop`（`:93`） |
| 过载 | 探针未启 | SHED_PERMILLE=0，不过载拦截 | `maybeShed`（`:25-28`） |
| 时钟 | 启动 nanoTime 脏值 | 相对判定 + 模减法，零影响 | ClockSource + TokenCodec（§5.1） |
| 配置 | 非法参数 | 构造期即拒 | PolicyBuilder.build（§5.5） |
| 热更新 | 在途脏 release | versionMatch=false 跳过 EWMA | `release`（`:69`） |

---

## 8. TPS 突增 / 突降 / 抖动（专项反思 + 实测验证）

> **反思命题**：流量速率的剧烈波动（突发、骤停、抖动）是否会破坏四种算法的正确性？下面逐项推导，并附实测结论。对应测试 `TpsDynamicsBreakerTest` / `TpsDynamicsTokenBucketTest` / `TpsDynamicsConcurrencyTest` / `TpsDynamicsSystemOverloadTest` / `TpsDynamicsEngineTest`。

### 8.1 EWMA 熔断器——本质是低通滤波器，突发被正确阻尼

**第一性原理**：α 只依赖 `Δt`。突发（高 TPS）下相邻样本 `Δt→0`，命中一阶泰勒分支 `α≈Δt/τ`（极小）。每个样本对 ppm 的贡献 `α·(X_t−EWMA)` 极小。**这是设计特性而非缺陷**：突发内的失败高度相关（往往是同一根因一次性触发），不应让单个瞬态事件推翻历史健康度。只有错误率**持续 τ 时长**，ppm 才爬升到阈值——这正是"时间衰减"相对"样本计数衰减"的核心优势（v1 缺陷，见 §1.1）。

**实测结论（`TpsDynamicsBreakerTest`）**：
- `microBurstFailuresDampenedByLowAlpha`：50 个失败挤在 1ms 内（priming 后 `Δt=0/1`），ppm 远低于 500_000，不跳闸。✅ 阻尼成立。
- `sustainedErrorsOverTauHorizonTripBreaker`：失败按 τ 间隔持续 5 次，α≈0.632，ppm 爬升过阈值跳闸。✅ 持续错误仍被捕获。
- `jitteredIntervalsStillTripWhenSustained`：间隔剧烈抖动（亚毫秒/多秒/τ 级混杂），只要持续错误足够久仍跳闸。✅ 抖动不漏判。
- `silenceThenSuccessFullyResetsPpm`：高 ppm 后长静默，`u≥8→α=1`，单次成功把 ppm 重置为 0。✅ 突降后快速"失忆"。
- `failureBurstThenSuccessBurstStaysNearBaseline`：失败突发→成功突发，ppm 始终贴近基线，不震荡。✅ 双向阻尼。
- `zeroDtSamplesDoNotShiftEwma`：`Δt=0→α=0`，同瞬样本不移动 ppm。✅ 退化态正确。
- `clockReversalWithin24BitDoesNotCorruptEwma`：时钟倒退，`dt` 经模减法变为巨大值→`α=1`→下一个样本全量重播种，**不腐蚀**，只是单样本权重异常（随后自愈）。✅ 优雅降级。

**边界发现（重要）**：`lastUpdateMs` 初值为 0。突发中**第一个**样本若发生在 `now>0`（如 `now=1000`），其 `dt=now−0=1000` 会命中 `α≈0.632`，单发就把 ppm 拉到 ~632_000。这是"冷启动首样本"效应——priming 后才进入纯突发阻尼区。测试中通过显式 priming 规避；生产中首请求即获得相对时间 0，此效应不显现。

### 8.2 令牌桶——突发被 capacity 封顶，恢复严格等于 qps

**第一性原理**：令牌桶的数学本质是"平均速率=qps、突发上限=capacity"。突发耗尽 capacity 后立即被限流，**不向未来借令牌**。恢复速率严格 `add=(now−tLast)·qps/1000`，与请求到达模式无关。

**实测结论（`TpsDynamicsTokenBucketTest`）**：
- `spikeDrainsBucketThenBlocksImmediateFollowup`：1000 突发耗尽后，同瞬跟进被阻断。✅ 不借未来令牌。
- `spikeThenPartialRefillResumesAtRate`：耗尽后 1ms 恰好补 1 令牌（qps=1000）。✅ 恢复严格守速率。
- `longIdleAfterSpikeRestoresFullBurst`：耗尽后长空闲，capacity 全恢复。✅ 突降恢复。
- `jitteredInterArrivalNoOverdraft`：抖动间隔下，放行数严格受 `capacity + 已补充令牌` 封顶，**无透支**。✅ 抖动安全。
- `backwardClockBlocksRatherThanCorrupts`：时钟倒退使 `add<0`→`nTok<1`→**阻断而非腐蚀**；状态字段不变。✅ 优雅降级。

### 8.3 并发——突发下过冲是已知权衡，release 始终回段

**第一性原理**：`sum≥limit` 判定与 `incrementAndGet` 非原子，极端并发突发下多线程可同时通过判定→瞬时过冲。但并发数无全局不变量（非 QPS 上限），过冲可接受。`bucketIdx` 入 token，release 据此回段，跨线程不漂移。

**实测结论（`TpsDynamicsConcurrencyTest`）**：突发下过冲有界（`sum ∈ [0, 线程数]`）；突发后释放全部归零，无泄漏。

### 8.4 系统过载——与 TPS 完全解耦，仅响应 CPU

**第一性原理（关键）**：`SystemOverload` 的信号是 **CPU**，**不是调用速率**。`maybeShed()` 只读 `SHED_PERMILLE`（探针每 1s 按 CPU 更新）。**TPS 突发本身不改变丢弃率**——只有 CPU 真的被抬高才触发。这避免了"流量突发→误判过载→误杀"。

**实测结论（`TpsDynamicsSystemOverloadTest`）**：
- `tpsSpikeAloneDoesNotTriggerShedding`：`SHED_PERMILLE=0` 下 10 万次急促 `maybeShed()` 全程 0 丢弃。✅ TPS 解耦。
- `statisticalDropRateMatchesPermilleAtHighTps`：`SHED_PERMILLE=200` 下 10 万次调用，丢弃率≈0.20±0.01。✅ 概率丢弃统计正确。
- `cpuJitterAtBoundaryDoesNotOscillateShedLevel`：CPU 在 55–62% 抖动（exit 阈值 50 以上），`SHED_PERMILLE` 稳定 200 不横跳。✅ 迟滞抗抖动。
- `sustainedCpuSpikeEscalatesGradedLevels`：CPU 60→80→90，等级 200→500→800。✅ 分级单调上升。

### 8.5 反思总结表

| 场景 | EWMA | 令牌桶 | 并发 | 系统过载 |
|---|---|---|---|---|
| 突发（spike） | 低通阻尼，不误跳闸 | capacity 封顶，不透支 | 有界过冲（已知权衡） | **解耦**，仅 CPU 触发 |
| 突降（drop） | `α=1` 快速遗忘 | 长空闲恢复 capacity | 自然排空 | CPU 降→迟滞后降级 |
| 抖动（jitter） | 自适应 Δt，不漏判不误判 | 逐间隔核算，无误差 | 不敏感 | 迟滞防边界横跳 |
| 时钟倒退 | 模减法→巨大 dt→α=1 重播种 | `add<0`→阻断不腐蚀 | 不涉及 | 不涉及 |

**结论**：四种算法在 TPS 突增/突降/抖动下**均保持正确**，无破坏性缺陷。唯一的设计权衡是并发突发过冲（已记录）与 EWMA 冷启动首样本效应（priming 可消除）。

---

## 10. 多服务隔离与雪崩预防（Multi-Service Isolation & Avalanche Prevention）

> **反思命题**：一个组件（SDK 库）被多个服务的治理规则共享，一个服务熔断限流，是否会**传染**其他服务，导致级联雪崩？

### 10.1 隔离模型：按 resourceId 独立状态

该库的核心隔离保证是**每个资源拥有完全独立的运行时状态**。就代码结构而言：

```
ResourceManager.STATES[resourceId]  // 每个 resourceId 独享一个 ResourceState 对象
ResourceManager.CONFIGS[resourceId] // 每个 resourceId 独享一个 ResourceConfig 对象
断路器状态  → ResourceState.breakerState (AtomicLong)      — 互不干扰
令牌桶状态  → ResourceState.bucketState (AtomicLong)        — 互不干扰
EWMA 错误率 → ResourceState.ewmaState (AtomicLong)          — 互不干扰
并发计数    → ResourceState.concurrency[16] (AtomicInteger[]) — 互不干扰
观测统计    → ResourceState.passCount / blockCount (LongAdder) — 互不干扰
```

两个不同的资源（比如 `svc_a` 与 `svc_b`）通过不同的 `resourceId` 索引到**不同的 `ResourceState` 对象**，彼此在内存中完全隔离。一个资源的断路器跳闸（EWMA ppm 飙升、状态机迁移 OPEN）**不会改变另一个资源的 `breakerState` 的任何一个 bit**。

### 10.2 唯一全局耦合点：SystemOverload（已设计，非泄漏）

全库**唯一**跨资源的可变共享状态是 `SystemOverload.SHED_PERMILLE`：

```
SystemOverload.java:16  static volatile int SHED_PERMILLE = 0;
```

这是**刻意的架构决策**而非泄漏：
- 系统过载（CPU 超标）是进程级信号，理应影响**所有**资源。若一台机器 CPU>90%，无论哪个服务的流量都应降载。
- 判定是**概率性的**（千分比丢弃），而非全有/全无。`SHED_PERMILLE` 只是一个丢弃率，不直接阻断任何资源。
- 探针每 1s 更新一次，`maybeShed()` 只在热路径做单次 volatile 读，无锁竞争。

所有其他静态变量都是**只读常量**（`BlockCode`、`TokenCodec` 的 bit 常量、`EwmaAlpha.EXP_LUT`），不构成耦合。

### 10.3 雪崩概率分析

**什么条件下一个服务故障会导致其他服务雪崩？**

1. **线程池共享**：如果所有服务共用同一线程池，服务 A 的慢调用填满线程池，服务 B 的请求将排队等待 → 雪崩。**这不是本库能控制的范围**——线程池治理属于业务/框架层。

2. **系统过载误判**：服务 A 的 CPU 突发（如密集计算）拉高 CPU → `SHED_PERMILLE` 上升 → 服务 B 被概率丢弃。**这是真实但有限的耦合**——`SHED_PERMILLE` 的上升基于实时 CPU，CPU 高时丢弃确实合理；当服务 B 本身 CPU 负担很轻时，A 的计算突发可能"误伤" B。这是单进程部署多服务的固有限制，不可通过库设计消除。

3. **内存泄漏 / GC 压力**：如果服务 A 导致频繁 Full GC（Stop-The-World），所有服务暂停。**同属进程级限制**，非库隔离设计能解决的。

4. **资源耗尽**：`ResourceManager` 最多 1024 个资源（`MAX_RESOURCES`），一个服务"恶意注册"不会耗尽限额（每服务通常 1-10 个资源，1024 远超实际所需）。

### 10.4 实验验证（`ResourceIsolationTest` + `ResourceIsolationBreakerTest`）

| 测试 | 场景 | 结果 |
|---|---|---|
| `tokenBucketDrainIsPerResource` | 耗尽 A 的令牌 → B 仍有满突发 | ✅ 令牌隔离 |
| `concurrencySaturationIsPerResource` | 饱和 A 的并发 → B 仍有空槽 | ✅ 并发隔离 |
| `statCountersArePerResource` | A 有 1pass+1block → B 仍是 0/0 | ✅ 计数隔离 |
| `avalancheScenarioAResourceFailsBStaysHealthy` | A 持续失败→跳闸 → B 正常放行、blockCount=0 | ✅ 无雪崩 |
| `breakerTripAtEngineLevelIsPerResource` | A 跳闸后 → B 正常通过、EWMA ppm=0 | ✅ 断路器隔离 |
| `systemOverloadIsTheOnlyCrossResourceCoupling` | 全局过载→都丢弃；清除→都恢复 | ✅ 唯一全局耦合 |
| `breakerTripIsPerResource` | A 的 breakerState 跳闸后 B 仍是 CLOSED | ✅ 状态隔离 |
| `ewmaErrorRateIsPerResource` | A 的 EWMA ppm~>800k → B ppm=0（无污染） | ✅ EWMA 隔离 |
| `generationIsPerResource` | A 的 breaker gen 变了 → B gen=0 不变 | ✅ 代际隔离 |

### 10.5 结论

**是，支持多服务**——一个资源的熔断/限流/并发状态**完全不泄漏**到另一个资源。**唯一**的跨资源耦合是 `SystemOverload`（全局 CPU 过载保护），这是有意设计的进程级安全网，不是一个服务故障传染给另一个服务的"漏洞"。真正的雪崩风险（线程池耗尽、GC 抖动）属于线程/内存共享层面，非本治理库能消除。

---

## 11. 与其它文档的关系

- 位布局权威定义：`04_DATA_MODEL.md`。
- 模块职责与依赖：`02_CORE_MODULES.md`（§3 各能力小节已加"详见 07_ALGORITHM_DEEP_DIVE"交叉引用）。
- 业务规则溯源：`docs/brd/design.md` §4（算法）、§6（防坑）、§8（热更新）。
- 不变量：`memory/constitution.md` §"Project-Specific Invariants"。
