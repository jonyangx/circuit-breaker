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
        ResourceState st = ResourceManager.STATES.get(resourceId);
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
        // Defect 6 fix: encode BEFORE counting. If encode threw (pathological timeMs/resourceId),
        // passCount must not increment for a call that never got a token — count only after success.
        long token = TokenCodec.encode(now, resourceId, cfg.version, bucketIdx, cfg.mask);
        st.passCount.increment(); // count only after all gates pass + token encoded (no passed-but-uncounted)
        return token;
    }

    public static void release(int resourceId, long token, boolean success) {
        // Defensive validation: check resourceId BEFORE token<0 so caller bugs surface
        // even for blocked tokens (consistent with tryAcquire).
        if (resourceId < 0 || resourceId >= ResourceManager.MAX_RESOURCES) {
            throw new IllegalArgumentException("resourceId out of range: " + resourceId);
        }
        ResourceState st = ResourceManager.STATES.get(resourceId);
        if (st == null) {
            throw new IllegalArgumentException("unregistered resourceId: " + resourceId);
        }

        if (token < 0) return; // blocked token carries no resource state to release (BR-004)
        ResourceConfig cfg = ResourceManager.CONFIGS.get(resourceId);
        long now = ClockSource.nowRelMs();
        int bucketIdx = TokenCodec.decodeBucket(token);
        int version = TokenCodec.decodeVersion(token);
        int mask = TokenCodec.decodeMask(token);

        // BR-053: cross-resource-release defense — decode and validate embedded resourceId.
        int tokenRid = TokenCodec.decodeResourceId(token);
        if (tokenRid != resourceId) {
            throw new IllegalArgumentException(
                "token/resourceId mismatch: token belongs to resource " + tokenRid +
                " but release() was called with resourceId=" + resourceId +
                " (possible cross-resource bug in reactive pipeline)");
        }

        // P2 fix: release uses the mask embedded in the token (acquire-time capabilities), NOT the
        // current config mask. If concurrency/circuit breaker were enabled when the token was acquired,
        // they must be released regardless of hot-reload — otherwise the slot leaks forever.
        if ((mask & ResourceConfig.MASK_CONCURRENCY) != 0) {
            SegmentedConcurrency.release(st, bucketIdx); // thread-agnostic rollback (BR-032)
        }
        if ((mask & ResourceConfig.MASK_CIRCUIT_BREAKER) != 0) {
            boolean versionMatch = (version == (cfg.version & TokenCodec.VERSION_MASK)); // BR-052
            EwmaCircuitBreaker.release(st, now, success, cfg, versionMatch);
        }
    }

    public static void release(int resourceId, long token, Outcome outcome) {
        // Delegates to the boolean path for SUCCESS/FAILURE; CANCELLED releases the concurrency
        // slot but carries no health signal (a cancelled call never reached the downstream), so it
        // must not pollute the breaker EWMA (AA Defect 1: subscribe-then-cancel availability attack).
        if (outcome == Outcome.CANCELLED) {
            releaseCancelled(resourceId, token);
        } else {
            release(resourceId, token, outcome == Outcome.SUCCESS);
        }
    }

    private static void releaseCancelled(int resourceId, long token) {
        if (resourceId < 0 || resourceId >= ResourceManager.MAX_RESOURCES) {
            throw new IllegalArgumentException("resourceId out of range: " + resourceId);
        }
        ResourceState st = ResourceManager.STATES.get(resourceId);
        if (st == null) {
            throw new IllegalArgumentException("unregistered resourceId: " + resourceId);
        }
        if (token < 0) return; // blocked token carries no resource state to release (BR-004)

        int bucketIdx = TokenCodec.decodeBucket(token);
        int mask = TokenCodec.decodeMask(token);

        int tokenRid = TokenCodec.decodeResourceId(token);
        if (tokenRid != resourceId) {
            throw new IllegalArgumentException(
                "token/resourceId mismatch: token belongs to resource " + tokenRid +
                " but release() was called with resourceId=" + resourceId +
                " (possible cross-resource bug in reactive pipeline)");
        }

        // Same slot-release semantics as the success/failure path (token-embedded mask, BR-053),
        // but deliberately skips the EWMA update — cancellation is not a health signal.
        if ((mask & ResourceConfig.MASK_CONCURRENCY) != 0) {
            SegmentedConcurrency.release(st, bucketIdx);
        }
    }
}
