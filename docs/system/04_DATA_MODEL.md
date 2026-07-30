# 04 数据模型（Data Model）

> 本库为内存级嵌入式库，**无数据库**。本"数据模型"为运行时状态结构（bit-packed `long` 与数组）的权威定义。
> 来源：`src/main/java/dev/circuitbreaker/core/**` · 与 `docs/domains/001-circuit-breaker/artifacts/data-model.md` 对齐（已按合并后代码更新）。

## 1. 资源寻址（数组，废弃 Map）
- `CONFIGS : AtomicReferenceArray<ResourceConfig>`（RCU 安全发布，`ResourceManager.java:13`）
- `STATES : final ResourceState[]`（set-once，跨版本稳定，`:14`）
- `resourceId ∈ [0, 1023]`（`MAX_RESOURCES`，`ResourceManager.java:12`）

## 2. ResourceConfig（不可变实体，`ResourceConfig.java`）
9 个 `public final` 字段：`mask`(int) · `qps`(long) · `capacity`(long) · `errThresholdPpm`(int,0..1e6) · `minCalls`(int) · `openMillis`(long) · `ewmaTauMs`(long) · `concurrencyLimit`(int) · `version`(int)。整体替换（RCU）；version 低 6 位进 token。

## 3. ResourceState（聚合根，`ResourceState.java`）
| 字段 | 类型 | 作用 |
|---|---|---|
| `bucketState` | AtomicLong | 令牌桶：高42位 Time \| 低22位 Tokens |
| `breakerState` | AtomicLong | 熔断：[state:2][gen:4][endTimeMs:58] |
| `ewmaState` | AtomicLong | EWMA：[gen:4][lastUpdateMs:24][count:16][ppm:20] |
| `concurrency[16]` | AtomicInteger[] | 分段并发近似（SEG=16） |
| `passCount` / `blockCount` | LongAdder | 只增不清观测计数 |

`ewmaErrorRatePpm()`：低 20 位 ppm 只读访问器。`@Contended` 填充待办（伪共享优化）。

## 4. 64 位 Token（`TokenCodec.java`）
```
 bit:  63    62 .......... 22   21 .. 16   15 . 12   11 ....... 0
      [sign][      time 41       ][version6][bucket4][  mask 12 ]
        0     相对毫秒时间戳        cfg.version  段索引    生效能力掩码
```
- 符号位恒 0（`token<0` 即阻断）；阻断码全负。
- RT 模减法：`rtMs = (now − decodeTime) & TIME_MASK`。
- 位宽：MASK=12 / BUCKET=4 / VERSION=6 / TIME=41；SHIFT：MASK=0/BUCKET=12/VERSION=16/TIME=22。
- token 携 version+bucketIdx → release 线程无关。

## 5. ewmaState 位布局（自包含 Δt 与代际）
| 位 | 宽 | 字段 | 说明 |
|---|---|---|---|
| 60–63 | 4 | generation | 镜像 breaker 代际 |
| 36–59 | 24 | lastUpdateMs | Δt=(now−last)&0xFFFFFF |
| 20–35 | 16 | count | 本代样本数，饱和 65535 |
| 0–19 | 20 | ppm | 错误率定点，2²⁰≥1e6 |

## 6. breakerState 位布局（携带代际）
| 位 | 宽 | 字段 | 说明 |
|---|---|---|---|
| 62–63 | 2 | state | 00 CLOSED / 01 OPEN / 10 HALF_OPEN |
| 58–61 | 4 | generation | 权威代际，迁移 +1（循环 16）；HALF_OPEN 时 endTime 兼作探路截止 |
| 0–57 | 58 | endTimeMs | OPEN 结束绝对 ms / HALF_OPEN 探路截止 |

## 7. bucketState 位布局（令牌桶）
高 42 位 Time_last（≈139 年） \| 低 22 位 Tokens（≤4,194,303）。refill `add=(now−tLast)*qps/1000`，nTok cap TOKEN_MASK。

## 8. 关系与不变量
- token.version == acquire 时 CONFIGS.version（低6位）；release 校验换代。
- token.bucketIdx == acquire 路由段；release 回同段。
- generation：breakerState 权威，ewmaState 镜像；迁移只动 breakerState，EWMA 代际惰性重播种。
- ppm 凸组合恒 ∈[0,1e6]；count 饱和 65535。
