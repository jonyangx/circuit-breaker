package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;

/**
 * Time-decay EWMA breaker + 3-state machine + generation (UC-005).
 * BR-020 (time decay), BR-022 (ppm fixed-point), BR-023 (minCalls), BR-024 (generation/ABA), BR-025 (state machine).
 *
 * ewmaState:   [gen:8 @56-63][lastUpdateMs:20 @36-55][count:16 @20-35][ppm:20 @0-19]
 *   — lastUpdateMs is stored at 16ms quantum (nowMs>>4), extending the 20-bit field's wrap
 *     horizon from 17.5min to 2^24 ms ≈ 4.66h. A 20-bit raw ms field would alias gaps ≥17.5min
 *     to dt≈0, freezing stale error rate that can false-trip; quantization (16ms/tick) makes the
 *     bad window [k·4.66h, k·4.66h+8τ) with τ≥1s → probability negligible.
 * breakerState:[state:2 @62-63][gen:8 @54-61][endTimeMs:54 @0-53]
 *
 * transition() is the ONLY entry that bumps generation; a stale-generation EWMA is
 * lazily re-seeded on the next update (no explicit clear CAS).
 */
public final class EwmaCircuitBreaker {
    static final int CLOSED = 0, OPEN = 1, HALF_OPEN = 2;
    private static final int PPM_SUCCESS = 0;
    private static final int PPM_FAIL = 1_000_000;
    private static final int EW_COUNT_MAX = 0xFFFF; // 16-bit count field saturates (never wraps) — see EW_COUNT_MASK
    /** lastUpdateMs quantum: store nowMs>>4 so a 20-bit field spans 2^24 ms (4.66h) not 17.5min. */
    private static final int EW_LAST_Q_SHIFT = 4; // 16 ms per stored tick

    // ewmaState masks
    private static final long EW_GEN_MASK = 0xFFL, EW_LAST_MASK = 0xFFFFFL, EW_COUNT_MASK = 0xFFFFL, EW_PPM_MASK = 0xFFFFFL;
    private static final int EW_GEN_SHIFT = 56, EW_LAST_SHIFT = 36, EW_COUNT_SHIFT = 20;
    // breakerState masks
    private static final long BR_STATE_MASK = 0x3L, BR_GEN_MASK = 0xFFL;
    private static final long BR_END_MASK = (1L << 54) - 1;
    private static final int BR_STATE_SHIFT = 62, BR_GEN_SHIFT = 54;

    private EwmaCircuitBreaker() {}

    public static boolean tryAcquire(ResourceState st, ResourceConfig cfg, long nowMs) {
        long b = st.breakerState.get();
        int s = brState(b);
        if (s == CLOSED) {
            return true;
        }
        if (s == OPEN) {
            if (nowMs >= brEnd(b)) {
                // race to transition OPEN → HALF_OPEN; single winner becomes the probe.
                // endTime here doubles as the probe deadline (nowMs + openMillis), so a lost probe
                // can self-heal (see HALF_OPEN branch) — no governance-side timer needed.
                boolean won = transition(st, OPEN, HALF_OPEN, nowMs + cfg.openMillis);
                if (won) {
                    // P1 fix: record this probe's generation so only its own release can resolve
                    // HALF_OPEN. A stale release from a CLOSED-era request carries a prior generation
                    // and is rejected in release() (prevents stale-release hijack of the probe).
                    st.probeGen.set(brGen(st.breakerState.get()));
                }
                return won;
            }
            return false;
        }
        // HALF_OPEN: a single probe is in flight (the OPEN→HALF_OPEN winner). Others block.
        // Self-heal (A1): if the probe did not resolve before its deadline (lost / forgotten release),
        // re-arm the OPEN cycle so a fresh probe is elected after openMillis — never stuck forever.
        if (nowMs >= brEnd(b)) {
            transition(st, HALF_OPEN, OPEN, nowMs + cfg.openMillis);
            st.probeGen.set(-1L); // probe invalidated on re-arm
        }
        return false;
    }

    public static void release(ResourceState st, long nowMs, boolean ok, ResourceConfig cfg, boolean verMatch) {
        long b = st.breakerState.get();
        int s = brState(b);
        if (s == HALF_OPEN) {
            // P1 fix: only the elected probe's release (generation matches probeGen) may resolve
            // HALF_OPEN. Stale releases from prior CLOSED/OPEN eras are ignored — they must NOT
            // hijack the probe outcome by transitioning HALF_OPEN→CLOSED/OPEN prematurely.
            if (brGen(b) == st.probeGen.get()) {
                transition(st, HALF_OPEN, ok ? CLOSED : OPEN, ok ? 0L : nowMs + cfg.openMillis);
            }
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
            int gNext = (brGen(cur) + 1) & 0xFF;                    // 8-bit generation (was 4)
            long next = packBreaker(to, gNext, endTimeMs);
            if (st.breakerState.compareAndSet(cur, next)) {
                return true;
            }
        }
    }

    static void updateEwma(ResourceState st, long nowMs, int xPpm, ResourceConfig cfg) {
        long nowQ = nowMs >> EW_LAST_Q_SHIFT; // 16ms-quantized reference (extends wrap to 2^24 ms ≈ 4.66h)
        int gNow = brGen(st.breakerState.get());
        for (;;) {
            long cur = st.ewmaState.get();
            long next;
            if (ewGen(cur) != gNow) {
                // stale generation (a transition happened) → re-seed (equivalent to "clear on entering CLOSED").
                next = packEwma(gNow, nowQ, 1, xPpm);
            } else {
                long dtQ = (nowQ - ewLast(cur)) & EW_LAST_MASK; // 20-bit modular, 16ms units
                long dtMs = dtQ << EW_LAST_Q_SHIFT;           // back to ms for α computation
                float a = EwmaAlpha.alpha(dtMs, cfg.ewmaTauMs);
                int cnt = (int) Math.min(EW_COUNT_MAX, ewCount(cur) + 1); // saturate, never wrap (enables minCalls comparison)
                int ppm = applyDecay(ewPpm(cur), xPpm, a);
                next = packEwma(gNow, nowQ, cnt, ppm);
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
    /** Pack ewmaState: [gen:8 @56-63][lastUpdateMs:20 @36-55, 16ms quantum][count:16 @20-35][ppm:20 @0-19]. */
    private static long packEwma(int gen, long lastQ, int count, int ppm) {
        return ((gen & EW_GEN_MASK) << EW_GEN_SHIFT)
             | ((lastQ & EW_LAST_MASK) << EW_LAST_SHIFT)
             | ((count & EW_COUNT_MASK) << EW_COUNT_SHIFT)
             | (ppm & EW_PPM_MASK);
    }
    /** Pack breakerState: [state:2 @62-63][gen:8 @54-61][endTimeMs:54 @0-53]. */
    private static long packBreaker(int state, int gen, long endTime) {
        return ((state & BR_STATE_MASK) << BR_STATE_SHIFT)
             | ((gen & BR_GEN_MASK) << BR_GEN_SHIFT)
             | (endTime & BR_END_MASK);
    }
    /** Unpack breaker state [62-63]. */
    static int brState(long b) { return (int) (b >>> BR_STATE_SHIFT); }
    /** Unpack breaker generation [58-61]. */
    static int brGen(long b) { return (int) ((b >>> BR_GEN_SHIFT) & BR_GEN_MASK); }
    /** Unpack breaker endTime [0-57]. */
    private static long brEnd(long b) { return b & BR_END_MASK; }
    /** Unpack ewma generation [56-63]. */
    static int ewGen(long e) { return (int) (e >>> EW_GEN_SHIFT); }
    /** Unpack ewma lastUpdateMs [36-55] (16ms quantum; multiply by 16 for absolute ms). */
    private static long ewLast(long e) { return (e >>> EW_LAST_SHIFT) & EW_LAST_MASK; }
    /** Unpack ewma sample count [20-35]. */
    private static int ewCount(long e) { return (int) ((e >>> EW_COUNT_SHIFT) & EW_COUNT_MASK); }
    /** Unpack ewma error-rate ppm [0-19]. */
    static int ewPpm(long e) { return (int) (e & EW_PPM_MASK); }
}
