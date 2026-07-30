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
        return new ResourceConfig(mask, qps, capacity, errThresholdPpm, minCalls,
                openMillis, ewmaTauMs, concurrencyLimit, 1);
    }
}
