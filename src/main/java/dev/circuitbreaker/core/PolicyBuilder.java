package dev.circuitbreaker.core;

import java.util.List;

/**
 * Fluent policy builder → immutable ResourceConfig (version=1). UC-001.
 *
 * <p>Optionally attach SLA facts via {@link #sla(PolicySpec.SlaFacts)} so {@link #build()}
 * enforces cross-parameter invariants (S1–S4) at construction time. Without SLA facts the builder
 * behaves exactly as before — the check is strictly opt-in and adds zero overhead.</p>
 */
public final class PolicyBuilder {
    private static final long TOKEN_FIELD_MAX = (1L << 22) - 1; // 4,194,303 (22-bit token field)

    private int mask = 0;
    private long qps = 0;
    private long capacity = 0;
    private int errThresholdPpm = 0;
    private int minCalls = 1;
    private long openMillis = 5_000L;
    private long ewmaTauMs = 5_000L;
    private int concurrencyLimit = 0;
    private PolicySpec.SlaFacts slaFacts; // optional; when set, build() enforces SLA invariants

    public PolicyBuilder enableRateLimit(long qps) {
        this.mask |= ResourceConfig.MASK_RATE_LIMIT;
        this.qps = qps;
        this.capacity = qps; // 1-second burst by default
        return this;
    }

    public PolicyBuilder enableCircuitBreaker(float errThreshold) {
        this.mask |= ResourceConfig.MASK_CIRCUIT_BREAKER;
        this.errThresholdPpm = (int) (errThreshold * 1_000_000);
        return this;
    }

    public PolicyBuilder minimumCalls(int n) { this.minCalls = n; return this; }
    public PolicyBuilder ewmaHalfLife(long ms) { this.ewmaTauMs = ms; return this; }
    public PolicyBuilder openMillis(long ms) { this.openMillis = ms; return this; }
    public PolicyBuilder enableConcurrency(int limit) { this.mask |= ResourceConfig.MASK_CONCURRENCY; this.concurrencyLimit = limit; return this; }

    /** Attach SLA facts so build() enforces cross-parameter invariants (S1–S4) via PolicySpec. */
    public PolicyBuilder sla(PolicySpec.SlaFacts facts) { this.slaFacts = facts; return this; }

    public ResourceConfig build() {
        // Defect 4 fix: openMillis too small → probe-timeout livelock. When openMillis < business RT,
        // a HALF_OPEN probe never completes before its endTime, so the breaker flips back to OPEN and
        // re-elects a probe forever — the breaker never recovers. Enforce a floor that keeps the
        // self-healing window (>= 2-3x typical RT) meaningful.
        if (openMillis < 100) {
            throw new IllegalArgumentException(
                "openMillis must be > 0 (>= 100ms to avoid probe-timeout livelock), got: " + openMillis);
        }
        if ((mask & ResourceConfig.MASK_RATE_LIMIT) != 0 && qps <= 0) {
            throw new IllegalArgumentException("qps must be > 0");
        }
        if ((mask & ResourceConfig.MASK_RATE_LIMIT) != 0 && qps > TOKEN_FIELD_MAX) {
            // token bucket's token field is 22 bits (~4.19M); a larger burst would overflow/corrupt it.
            throw new IllegalArgumentException("qps must be <= " + TOKEN_FIELD_MAX + " (22-bit token field)");
        }
        // AA §2.3 fix: capacity must also be <= TOKEN_FIELD_MAX. A malicious config could set
        // capacity far beyond the token field, causing LazyTokenBucket.seed() to silently clamp
        // (unexpected behavior) or overflow (state corruption).
        if ((mask & ResourceConfig.MASK_RATE_LIMIT) != 0 && capacity > TOKEN_FIELD_MAX) {
            throw new IllegalArgumentException("capacity must be <= " + TOKEN_FIELD_MAX + " (22-bit token field)");
        }
        if ((mask & ResourceConfig.MASK_CIRCUIT_BREAKER) != 0) {
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
        if ((mask & ResourceConfig.MASK_CONCURRENCY) != 0 && concurrencyLimit <= 0) {
            throw new IllegalArgumentException("concurrencyLimit must be > 0");
        }
        // P1 fix: warn when concurrencyLimit < SEG. SegmentedConcurrency enforces a per-segment cap of
        // ceil(limit/SEG); when limit < SEG that cap is 1, so the effective concurrency ceiling drops
        // well below the configured value (a single in-flight request in the probed segment blocks it).
        // This is a documented trade-off of lock-free segmentation, not an error — surface a warning so
        // operators notice the under-provisioning rather than failing the build.
        if ((mask & ResourceConfig.MASK_CONCURRENCY) != 0 && concurrencyLimit < ResourceState.SEG) {
            System.err.println(
                "[circuit-breaker] WARNING: concurrencyLimit=" + concurrencyLimit
                    + " < SEG=" + ResourceState.SEG
                    + " — segmented concurrency may block well below the configured limit; "
                    + "raise concurrencyLimit to >= SEG for accurate enforcement.");
        }
        ResourceConfig cfg = new ResourceConfig(mask, qps, capacity, errThresholdPpm, minCalls,
                openMillis, ewmaTauMs, concurrencyLimit, 1);
        enforceSlaInvariants(cfg);
        return cfg;
    }

    private void enforceSlaInvariants(ResourceConfig cfg) {
        if (slaFacts == null) {
            return; // opt-in: no SLA attached → check skipped (backward compatible)
        }
        List<PolicySpec.Finding> findings = PolicySpec.check(cfg, slaFacts);
        for (PolicySpec.Finding f : findings) {
            if (f.level == PolicySpec.Level.ERROR) {
                throw new IllegalArgumentException("policy violates SLA invariants: " + findings);
            }
        }
    }
}
