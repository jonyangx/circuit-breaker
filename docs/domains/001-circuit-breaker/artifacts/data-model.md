# Data Model：Circuit Breaker 运行时状态模型

> 本组件为内存级嵌入式库，**无数据库**。本"数据模型"描述运行时状态结构（bit-packed `long` 与数组），是跨模块共享契约的权威定义。来源：`docs/brd/design.md` §3.2 / §4。

## 1. 资源寻址（数组寻址，废弃 Map）

```
CONFIGS : volatile ResourceConfig[]   // 不可变参数，RCU 热换；CONFIGS[resourceId]
STATES  : final    ResourceState[]    // 长生命周期，永不随规则替换；STATES[resourceId]
```
`resourceId ∈ [0, 1023]`（全局唯一整数，启动/规则下发时分配）。

## 2. ResourceConfig（不可变值对象，整体替换）

| 字段 | 类型 | 说明 |
|------|------|------|
| `mask` | long/short | 能力位掩码（0x01 熔断 / 0x02 限流 / 0x04 并发） |
| `qps` | long | 每秒补充令牌数（tokens/sec）；refill 用 `dtMs×qps/1000`（ms 粒度） |
| `capacity` | long | 令牌桶容量 |
| `errThreshold` | int | 错误率阈值（ppm，0..1_000_000） |
| `minCalls` | int | 跳闸最小样本门槛（冷启动） |
| `openMillis` | long | 熔断开启时长（ms） |
| `ewmaTauMs` | long | EWMA 半衰期 τ（ms） |
| `concurrencyLimit` | int | 并发上限 |
| `version` | int | 配置版本号（低 10 位进入 token） |

## 3. ResourceState（聚合根，跨版本稳定）

| 字段 | 类型 | 作用 |
|------|------|------|
| `bucketState` | AtomicLong | 令牌桶：高 42 位 Time \| 低 22 位 Tokens |
| `breakerState` | AtomicLong | 熔断：[state:2][gen:8][endTimeMs:54] |
| `ewmaState` | AtomicLong | EWMA：[gen:8][lastUpdateMs:20][count:16][ppm:20] |
| `concurrency[]` | AtomicInteger[SEG] | 分段并发近似（SEG=8 或 16） |
| `passCount` | LongAdder | 放行计数（只增不清） |
| `blockCount` | LongAdder | 阻断计数（只增不清） |

> 令牌桶与 breakerState 施加 `@Contended` 填充隔离伪共享（`-XX:-RestrictContended`）。

## 4. 64 位 Token（贯穿调用始末）

```
 bit:  63    62 ...... 26   25 ..... 16   15 . 12   11 ....... 0
      [sign][    time 37     ][version10][bucket4][  mask 12 ]
        0     相对毫秒时间戳      cfg.version  段索引    生效能力掩码
```
- 符号位恒 0（`token < 0` 即阻断）；阻断码全负（-1/-2/-3/-4）。
- RT 计算：`rtMs = (nowRelMs - decodeTime(token)) & TIME_MASK`（模减法，抗截断/环绕）。
- `version`+`bucketIdx` 内嵌 → release 不依赖执行线程。

## 5. ewmaState 位布局（自包含 Δt 与代际）

| 位 | 宽 | 字段 | 说明 |
|----|----|------|------|
| 56–63 | 8 | generation | 镜像 breaker 代际 |
| 36–55 | 20 | lastUpdateMs | 上次更新相对 ms；Δt=(now−last)&0xFFFFF |
| 20–35 | 16 | count | 本代样本数，饱和于 65535 |
| 0–19 | 20 | ppm | 错误率定点，2^20=1_048_576 ≥ 1_000_000 |

## 6. breakerState 位布局（携带 generation）

| 位 | 宽 | 字段 | 说明 |
|----|----|------|------|
| 62–63 | 2 | state | 00 CLOSED / 01 OPEN / 10 HALF_OPEN |
| 54–61 | 8 | generation | 权威代际，迁移 +1（循环 256） |
| 0–53 | 54 | endTimeMs | OPEN 结束的绝对 ms |

## 7. 关系与不变量

- `version`（token 低 10 位）= acquire 命中的 `CONFIGS[resourceId].version`；release 时校验换代。
- `bucketIdx`（token）= acquire 路由到的并发段；release 据此回到同一段 `-1`。
- `generation`：breakerState 为权威源，ewmaState 镜像；迁移只动 breakerState，EWMA 靠代际标签惰性重播种。
