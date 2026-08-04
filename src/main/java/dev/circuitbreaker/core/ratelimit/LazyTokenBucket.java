package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazy, lock-free token bucket (single AtomicLong, NOT striped — BR-012).
 * UC-004; BR-010 (layout), BR-011 (lazy refill), BR-013 (float-zero-out).
 *
 * Layout: high 42 bits Time_last | low 22 bits Tokens.
 * Refill computed lazily on each acquire at ms granularity: add = dtMs * qps / 1000 (design §4.2.2
 * ratePerMs). When no full token is available (nTok < 1), Time_last is left untouched so the
 * interval keeps accumulating (BR-013 — prevents low-QPS starvation).
 */
public final class LazyTokenBucket {
    static final long TOKEN_MASK = (1L << 22) - 1;  // low 22 bits
    static final int TIME_SHIFT = 22;               // high 42 bits Time_last

    private LazyTokenBucket() {}

    /** Seed a freshly-registered bucket to its full burst capacity (tLast=0, tok=capacity). */
    public static void seed(ResourceState st, long capacity) {
        st.bucketState.set(Math.min(capacity, TOKEN_MASK));
    }

    /** @return true if a token was consumed (pass); false if rate-limited (caller maps to -3). */
    public static boolean tryAcquire(ResourceState st, ResourceConfig cfg, long nowMs) {
        AtomicLong state = st.bucketState;
        long qps = cfg.qps;
        long capacity = cfg.capacity;
        for (;;) {
            long cur = state.get();
            long tLast = cur >>> TIME_SHIFT;
            long tok = cur & TOKEN_MASK;
            long dtMs = Math.max(0L, nowMs - tLast);      // N3: clamp a non-monotonic anomaly to 0
            long add = refillTokens(dtMs, qps, capacity); // N1: ms-granularity, overflow-safe
            // cap at TOKEN_MASK so a capacity > 2^22-1 cannot overflow the 22-bit token field and
            // corrupt the adjacent Time field on the refill path (seed() already caps; this matches it).
            long nTok = Math.min(Math.min(capacity, tok + add), TOKEN_MASK);
            if (nTok < 1) {
                // BR-013: do not advance Time_last; leave state unchanged, interval accumulates.
                return false;
            }
            long next = (nowMs << TIME_SHIFT) | (nTok - 1);
            if (state.compareAndSet(cur, next)) {
                return true;
            }
        }
    }

    /**
     * Whole-token refill at ms granularity: {@code dtMs * qps / 1000}.
     *
     * <p>N1: the previous {@code (dtMs / 1000) * qps} quantized refill to whole seconds, so a
     * qps=1500 bucket drained to zero blocked for ~1s then burst 1500 (a step function, not a
     * smooth rate). Multiplying first restores the ms-granularity the design specifies.
     *
     * <p>Floor (truncating) semantics: at non-divisor qps the steady-state rate is the floor
     * (e.g. qps=1500 delivers ~1000/s after the burst) — accepted per the refactor decision and
     * consistent with BR-013's "whole token only" rule.
     *
     * <p>Overflow-safe: {@code dtMs * qps} could overflow long under pathological idle (≥~250yr at
     * max qps). Since the result is min-capped to the effective capacity anyway, saturate once
     * {@code dtMs} exceeds the time needed to fill to capacity.
     */
    private static long refillTokens(long dtMs, long qps, long capacity) {
        long cap = Math.min(capacity, TOKEN_MASK);
        if (qps <= 0 || cap <= 0 || dtMs <= 0) {
            return 0L;
        }
        // Defensive saturation check for pathological inputs
        if (dtMs >= (TOKEN_MASK + 1) || qps >= (Long.MAX_VALUE / 1000)) {
            return cap; // pathological input forced saturation
        }
        long dtSat = cap * 1000L / qps; // ms needed to refill to cap
        if (dtMs >= dtSat) {
            return cap;                  // saturated — skip the multiply (overflow-safe)
        }
        long add = dtMs * qps / 1000L;   // ms-granularity; dtMs < dtSat ⇒ no overflow
        // Final clamp prevents any residual overflow
        return Math.min(add, cap);
    }
}
