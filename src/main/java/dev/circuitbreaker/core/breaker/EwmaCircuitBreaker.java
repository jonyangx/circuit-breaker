package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;

/**
 * Time-decay EWMA breaker + 3-state machine + generation (UC-005).
 * BR-020 (time decay), BR-022 (ppm fixed-point), BR-023 (minCalls), BR-024 (generation/ABA), BR-025 (state machine).
 *
 * ewmaState:   [gen:4 @60-63][lastUpdateMs:24 @36-59][count:16 @20-35][ppm:20 @0-19]
 * breakerState:[state:2 @62-63][gen:4 @58-61][endTimeMs:58 @0-57]
 *
 * transition() is the ONLY entry that bumps generation; a stale-generation EWMA is
 * lazily re-seeded on the next update (no explicit clear CAS).
 */
public final class EwmaCircuitBreaker {
    static final int CLOSED = 0, OPEN = 1, HALF_OPEN = 2;
    private static final int PPM_SUCCESS = 0;
    private static final int PPM_FAIL = 1_000_000;

    // ewmaState masks
    private static final long EW_GEN_MASK = 0xFL, EW_LAST_MASK = 0xFFFFFFL, EW_COUNT_MASK = 0xFFFFL, EW_PPM_MASK = 0xFFFFFL;
    private static final int EW_GEN_SHIFT = 60, EW_LAST_SHIFT = 36, EW_COUNT_SHIFT = 20;
    // breakerState masks
    private static final long BR_STATE_MASK = 0x3L, BR_GEN_MASK = 0xFL;
    private static final long BR_END_MASK = (1L << 58) - 1;
    private static final int BR_STATE_SHIFT = 62, BR_GEN_SHIFT = 58;

    private EwmaCircuitBreaker() {}

    public static boolean tryAcquire(ResourceState st, long nowMs) {
        long b = st.breakerState.get();
        int s = brState(b);
        if (s == CLOSED) {
            return true;
        }
        if (s == OPEN) {
            if (nowMs >= brEnd(b)) {
                // race to transition OPEN → HALF_OPEN; single winner becomes the probe.
                return transition(st, OPEN, HALF_OPEN, 0L);
            }
            return false;
        }
        // HALF_OPEN: only the in-flight probe (the thread that won OPEN→HALF_OPEN) proceeds;
        // it already returned true at transition. Others block here.
        return false;
    }

    public static void release(ResourceState st, long nowMs, boolean ok, ResourceConfig cfg, boolean verMatch) {
        long b = st.breakerState.get();
        int s = brState(b);
        if (s == HALF_OPEN) {
            transition(st, HALF_OPEN, ok ? CLOSED : OPEN, ok ? 0L : nowMs + cfg.openMillis);
            return;
        }
        if (s == CLOSED) {
            if (verMatch) {
                updateEwma(st, nowMs, ok ? PPM_SUCCESS : PPM_FAIL, cfg);
            }
            // re-check trip condition under current authoritative generation
            long b2 = st.breakerState.get();
            if (brState(b2) == CLOSED) {
                int gNow = brGen(b2);
                long e = st.ewmaState.get();
                if (ewGen(e) == gNow
                        && ewCount(e) >= cfg.minCalls
                        && ewPpm(e) >= cfg.errThresholdPpm) {
                    transition(st, CLOSED, OPEN, nowMs + cfg.openMillis);
                }
            }
        }
    }

    /** Only entry that changes generation. Returns true if this thread performed the transition. */
    static boolean transition(ResourceState st, int from, int to, long endTimeMs) {
        for (;;) {
            long cur = st.breakerState.get();
            if (brState(cur) != from) {
                return false;
            }
            int gNext = (brGen(cur) + 1) & 0xF;
            long next = packBreaker(to, gNext, endTimeMs);
            if (st.breakerState.compareAndSet(cur, next)) {
                return true;
            }
        }
    }

    static void updateEwma(ResourceState st, long nowMs, int xPpm, ResourceConfig cfg) {
        int gNow = brGen(st.breakerState.get());
        for (;;) {
            long cur = st.ewmaState.get();
            long next;
            if (ewGen(cur) != gNow) {
                // stale generation (a transition happened) → re-seed (equivalent to "clear on entering CLOSED").
                next = packEwma(gNow, nowMs, 1, xPpm);
            } else {
                long dt = (nowMs - ewLast(cur)) & EW_LAST_MASK;
                float a = EwmaAlpha.alpha(dt, cfg.ewmaTauMs);
                int cnt = (int) Math.min(0xFFFFL, ewCount(cur) + 1);
                int ppm = applyDecay(ewPpm(cur), xPpm, a);
                next = packEwma(gNow, nowMs, cnt, ppm);
            }
            if (st.ewmaState.compareAndSet(cur, next)) {
                return;
            }
        }
    }

    private static int applyDecay(int ewmaPpm, int xPpm, float alpha) {
        return ewmaPpm + (int) Math.round(alpha * (xPpm - ewmaPpm));
    }

    // ---- packers / unpackers ----
    private static long packEwma(int gen, long lastMs, int count, int ppm) {
        return ((gen & EW_GEN_MASK) << EW_GEN_SHIFT)
             | ((lastMs & EW_LAST_MASK) << EW_LAST_SHIFT)
             | ((count & EW_COUNT_MASK) << EW_COUNT_SHIFT)
             | (ppm & EW_PPM_MASK);
    }
    private static long packBreaker(int state, int gen, long endTime) {
        return ((state & BR_STATE_MASK) << BR_STATE_SHIFT)
             | ((gen & BR_GEN_MASK) << BR_GEN_SHIFT)
             | (endTime & BR_END_MASK);
    }
    static int brState(long b) { return (int) (b >>> BR_STATE_SHIFT); }
    static int brGen(long b) { return (int) ((b >>> BR_GEN_SHIFT) & BR_GEN_MASK); }
    private static long brEnd(long b) { return b & BR_END_MASK; }
    private static int ewGen(long e) { return (int) (e >>> EW_GEN_SHIFT); }
    private static long ewLast(long e) { return (e >>> EW_LAST_SHIFT) & EW_LAST_MASK; }
    private static int ewCount(long e) { return (int) ((e >>> EW_COUNT_SHIFT) & EW_COUNT_MASK); }
    static int ewPpm(long e) { return (int) (e & EW_PPM_MASK); }
}
