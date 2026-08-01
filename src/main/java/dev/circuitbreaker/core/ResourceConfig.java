package dev.circuitbreaker.core;

/**
 * Immutable resource config (pure params, RCU-swappable). UC-001, UC-008; BR-002, BR-050.
 * Hot-update replaces the whole object (version+1); ResourceState is never rebuilt (BR-051).
 */
public final class ResourceConfig {
    /** Capability bitmask constants (also the authoritative bit positions for mask). */
    public static final int MASK_CIRCUIT_BREAKER = 0x01; // bit 0
    public static final int MASK_RATE_LIMIT      = 0x02; // bit 1
    public static final int MASK_CONCURRENCY      = 0x04; // bit 2

    public final int mask;             // capability bitmask 0x01/0x02/0x04
    public final long qps;             // refill rate in tokens/sec (lazy bucket)
    public final long capacity;        // bucket burst capacity
    public final int errThresholdPpm;  // breaker error-rate threshold (ppm 0..1_000_000)
    public final int minCalls;         // breaker minimum-sample cold-start threshold
    public final long openMillis;      // breaker open duration (ms)
    public final long ewmaTauMs;       // EWMA decay half-life τ (ms)
    public final int concurrencyLimit; // concurrency cap
    public final int version;          // config version (low 6 bits enter token)

    public ResourceConfig(int mask, long qps, long capacity, int errThresholdPpm,
                          int minCalls, long openMillis, long ewmaTauMs,
                          int concurrencyLimit, int version) {
        this.mask = mask;
        this.qps = qps;
        this.capacity = capacity;
        this.errThresholdPpm = errThresholdPpm;
        this.minCalls = minCalls;
        this.openMillis = openMillis;
        this.ewmaTauMs = ewmaTauMs;
        this.concurrencyLimit = concurrencyLimit;
        this.version = version;
    }
}
