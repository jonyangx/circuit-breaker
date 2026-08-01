package dev.circuitbreaker.core;

/**
 * Fluent policy builder → immutable ResourceConfig (version=1). UC-001.
 */
public final class PolicyBuilder {
    private int mask = 0;
    private long qps = 0;
    private long capacity = 0;
    private int errThresholdPpm = 0;
    private int minCalls = 1;
    private long openMillis = 5_000L;
    private long ewmaTauMs = 5_000L;
    private int concurrencyLimit = 0;

    public PolicyBuilder enableRateLimit(long qps) {
        this.mask |= 0x02;
        this.qps = qps;
        this.capacity = qps; // 1-second burst by default
        return this;
    }

    public PolicyBuilder enableCircuitBreaker(float errThreshold) {
        this.mask |= 0x01;
        this.errThresholdPpm = (int) (errThreshold * 1_000_000);
        return this;
    }

    public PolicyBuilder minimumCalls(int n) { this.minCalls = n; return this; }
    public PolicyBuilder ewmaHalfLife(long ms) { this.ewmaTauMs = ms; return this; }
    public PolicyBuilder openMillis(long ms) { this.openMillis = ms; return this; }
    public PolicyBuilder enableConcurrency(int limit) { this.mask |= 0x04; this.concurrencyLimit = limit; return this; }

    public ResourceConfig build() {
        if (openMillis <= 0) {
            throw new IllegalArgumentException("openMillis must be > 0");
        }
        if ((mask & 0x02) != 0 && qps <= 0) {
            throw new IllegalArgumentException("qps must be > 0");
        }
        if ((mask & 0x02) != 0 && qps > 4_194_303L) {
            // token bucket's token field is 22 bits (~4.19M); a larger burst would overflow/corrupt it.
            throw new IllegalArgumentException("qps must be <= 4_194_303 (22-bit token field)");
        }
        if ((mask & 0x01) != 0) {
            if (errThresholdPpm <= 0 || errThresholdPpm > 1_000_000) {
                throw new IllegalArgumentException("circuit-breaker errThreshold must be in (0, 1]");
            }
            if (ewmaTauMs <= 0) {
                throw new IllegalArgumentException("ewmaHalfLife must be > 0");
            }
            if (minCalls <= 0) {
                throw new IllegalArgumentException("minimumCalls must be > 0");
            }
            if (minCalls > 65535) {
                throw new IllegalArgumentException("minimumCalls must be <= 65535 (16-bit count limit)");
            }
        }
        if ((mask & 0x04) != 0 && concurrencyLimit <= 0) {
            throw new IllegalArgumentException("concurrencyLimit must be > 0");
        }
        return new ResourceConfig(mask, qps, capacity, errThresholdPpm, minCalls,
                openMillis, ewmaTauMs, concurrencyLimit, 1);
    }
}
