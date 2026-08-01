package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazy, lock-free token bucket (single AtomicLong, NOT striped — BR-012).
 * UC-004; BR-010 (layout), BR-011 (lazy refill), BR-013 (float-zero-out).
 *
 * Layout: high 42 bits Time_last | low 22 bits Tokens.
 * Refill computed lazily on each acquire: add = (now - tLast) * qps / 1000.
 * When no full token is available (nTok < 1), Time_last is left untouched so the
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
            long dtMs = nowMs - tLast;
        long add = (dtMs / 1000L) * qps;
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
}
