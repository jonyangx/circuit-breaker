package dev.circuitbreaker.core;

import dev.circuitbreaker.core.breaker.EwmaCircuitBreaker;
import dev.circuitbreaker.core.concurrency.SegmentedConcurrency;
import dev.circuitbreaker.core.ratelimit.LazyTokenBucket;
import dev.circuitbreaker.core.system.SystemOverload;

/**
 * Flat execution engine: public entry + bitmask dispatch (UC-002/003; BR-005).
 * Hot path is allocation-free: only primitive locals; token is a long.
 *
 *  1. system-overload probabilistic short-circuit (BR-040)
 *  2. read CONFIGS/STATES + monotonic now
 *  3. bitmask dispatch: MASK_CIRCUIT_BREAKER / MASK_RATE_LIMIT / MASK_CONCURRENCY
 *  4. all pass → pack token; any fail → negative block code (BR-004)
 */
public final class FlatExecutionEngine {

    private FlatExecutionEngine() {}

    public static long tryAcquire(int resourceId) {
        if (resourceId < 0 || resourceId >= ResourceManager.MAX_RESOURCES) {
            throw new IllegalArgumentException("resourceId out of range: " + resourceId);
        }
        ResourceState st = ResourceManager.STATES[resourceId];
        if (st == null) {
            throw new IllegalArgumentException("unregistered resourceId: " + resourceId);
        }
        // BR-040 system overload short-circuit (single volatile read)
        if (SystemOverload.maybeShed()) {
            st.blockCount.increment();
            return BlockCode.SYSTEM_OVERLOAD;
        }
        ResourceConfig cfg = ResourceManager.CONFIGS.get(resourceId);
        long now = ClockSource.nowRelMs();

        if ((cfg.mask & ResourceConfig.MASK_CIRCUIT_BREAKER) != 0 && !EwmaCircuitBreaker.tryAcquire(st, cfg, now)) {
            st.blockCount.increment();
            return BlockCode.CIRCUIT_BREAKER;
        }
        if ((cfg.mask & ResourceConfig.MASK_RATE_LIMIT) != 0 && !LazyTokenBucket.tryAcquire(st, cfg, now)) {
            st.blockCount.increment();
            return BlockCode.RATE_LIMITER;
        }
        int bucketIdx = 0;
        if ((cfg.mask & ResourceConfig.MASK_CONCURRENCY) != 0) {
            bucketIdx = SegmentedConcurrency.tryAcquire(st, cfg);
            if (bucketIdx < 0) {
                st.blockCount.increment();
                return BlockCode.CONCURRENCY;
            }
        }
        st.passCount.increment(); // count only after all gates pass + token encoded (no passed-but-uncounted)
        return TokenCodec.encode(now, cfg.version, bucketIdx, cfg.mask);
    }

    public static void release(int resourceId, long token, boolean success) {
        if (token < 0) return; // blocked token carries no resource state to release (BR-004)
        ResourceState st = ResourceManager.STATES[resourceId];
        ResourceConfig cfg = ResourceManager.CONFIGS.get(resourceId);
        long now = ClockSource.nowRelMs();
        int bucketIdx = TokenCodec.decodeBucket(token);
        int version = TokenCodec.decodeVersion(token);
        int mask = TokenCodec.decodeMask(token);

        if ((mask & ResourceConfig.MASK_CONCURRENCY) != 0) {
            SegmentedConcurrency.release(st, bucketIdx); // thread-agnostic rollback (BR-032)
        }
        if ((mask & ResourceConfig.MASK_CIRCUIT_BREAKER) != 0) {
            boolean versionMatch = (version == (cfg.version & TokenCodec.VERSION_MASK)); // BR-052
            EwmaCircuitBreaker.release(st, now, success, cfg, versionMatch);
        }
    }
}
