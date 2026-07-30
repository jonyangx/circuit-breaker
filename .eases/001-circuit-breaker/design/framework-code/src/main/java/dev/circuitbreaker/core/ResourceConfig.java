package dev.circuitbreaker.core;

/**
 * 不可变资源配置（纯参数，可 RCU 热换）。
 * 关联用例：UC-001（注册）、UC-008（热更新）；规则 BR-002（配置/状态分离）、BR-050（RCU）。
 * 实现步骤：record/不可变；version 每次 new 时 +1（低 6 位进入 token，BR-052）。
 */
public final class ResourceConfig {
    final long mask;            // 能力位掩码（0x01/0x02/0x04）
    final long ratePerMs;       // 令牌桶补充速率
    final long capacity;        // 令牌桶容量
    final int  errThresholdPpm; // 熔断错误率阈值 ppm（0..1_000_000）
    final int  minCalls;        // 熔断冷启动门槛
    final long openMillis;      // 熔断开启时长
    final long ewmaTauMs;       // EWMA 半衰期 τ
    final int  concurrencyLimit;// 并发上限
    final int  version;         // 配置版本号（低 6 位进 token）

    public ResourceConfig(long mask, long ratePerMs, long capacity, int errThresholdPpm,
                          int minCalls, long openMillis, long ewmaTauMs, int concurrencyLimit, int version) {
        throw new UnsupportedOperationException("TODO: 赋值所有 final 字段（不可变）");
    }

    public long mask() { throw new UnsupportedOperationException("TODO"); }
    public long ratePerMs() { throw new UnsupportedOperationException("TODO"); }
    public long capacity() { throw new UnsupportedOperationException("TODO"); }
    public int errThresholdPpm() { throw new UnsupportedOperationException("TODO"); }
    public int minCalls() { throw new UnsupportedOperationException("TODO"); }
    public long openMillis() { throw new UnsupportedOperationException("TODO"); }
    public long ewmaTauMs() { throw new UnsupportedOperationException("TODO"); }
    public int concurrencyLimit() { throw new UnsupportedOperationException("TODO"); }
    public int version() { throw new UnsupportedOperationException("TODO"); }
}
