package dev.circuitbreaker.core.concurrency;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Segmented approximate concurrency (AtomicInteger[SEG], TLR probe routing).
 * UC-006; BR-030 (segmented), BR-031 (TLR probe, not threadId), BR-032 (bucketIdx rollback same segment).
 * Sum is approximate — slight overshoot under extreme contention is traded for lock-freedom.
 */
public final class SegmentedConcurrency {
    private SegmentedConcurrency() {}

    /**
     * CRITICAL fix (AA §1.1): restore global hard limit enforcement.
     *
     * The earlier probabilistic-only check routinely violated the configured limit (PASS prob ≈ 81%
     * at limit=3, SEG=16). This fix enforces the hard global guarantee with bounded per-segment overshoot.
     *
     * Algorithm:
     *   1. Probe segment via ThreadLocalRandom (load distribution across segments)
     *   2. Per-segment cap: limitPerSeg = ceil(concurrencyLimit / SEG)
     *      Bounds worst-case overshoot to SEG-1 when limit ≫ SEG
     *      When limit < SEG, limitPerSeg = 1 ensures single-token-per-segment (no hot-segment saturation)
     *   3. Pessimistic pre-check (AA Defect 3): if the global total is already at the limit, reject
     *      without touching segment counters — kills the rollback storm at tight limits
     *   4. Increment the probed segment (optimistic, no CAS)
     *   5. Compute total concurrency (segment sum, O(16) lock-free read)
     *   6. Global limit check: if total > concurrencyLimit, rollback (decrement) and block
     *
     * Why increment-then-check (not check-then-increment)?
     *   - Pre-check on total is racy (another thread may increment concurrently)
     *   - Increment-then-check is exact: we see the post-increment total and enforce the limit atomically
     *   - Rollback on overflow is O(1) and rare (only when limit is tight)
     *   - The pre-check is a fast-path rejection, not a guarantee: it only filters the common
     *     already-saturated case; the increment-then-check remains the correctness backstop
     *
     * Performance: O(16) segment sum on the happy path (1 microop per iteration) — still nanosecond-scale.
     * Alternative: a global LongAdder for exact total, but that adds contended hot-path writes.
     */
    public static int tryAcquire(ResourceState st, ResourceConfig cfg) {
        int bidx = ThreadLocalRandom.current().nextInt(ResourceState.SEG);

        // Per-segment cap: bounds overshoot to at most SEG-1 when concurrencyLimit ≫ SEG
        // When concurrencyLimit < SEG, limitPerSeg = 1 → single token per segment (no hot-segment saturation)
        int limitPerSeg = (int) Math.ceil(cfg.concurrencyLimit / (double) ResourceState.SEG);
        if (st.concurrency[bidx].get() >= limitPerSeg) {
            return -1;
        }

        // AA Defect 3 fix + HT-3 optimization: pessimistic pre-check — if the global total is
        // already at the limit, reject without touching the segment counters. Eliminates the
        // rollback storm under sustained contention at a tight limit (increment → sum → decrement
        // churn). Conditional on limitPerSeg <= 2 (i.e. concurrencyLimit <= 32 with SEG=16): when
        // the limit is wide the already-saturated case is rare, so the 16-read pre-check is almost
        // always wasted — the post-increment sum + (rare) rollback below is the cheaper backstop.
        // Happy path drops from 33 volatile reads + 1 write to 17 reads + 1 write (AA HT-3
        // quantification). Global enforcement is UNCHANGED: the post-increment sum below is exact
        // and remains the correctness backstop regardless of this fast-path filter.
        if (limitPerSeg <= 2 && st.sumConcurrency() >= cfg.concurrencyLimit) {
            return -1;
        }

        // Optimistic increment (no CAS: segment counters are AtomicInteger, not LongAdder)
        st.concurrency[bidx].incrementAndGet();

        // Global limit check: sum all segments and enforce hard limit
        long total = 0;
        for (int i = 0; i < ResourceState.SEG; i++) {
            total += st.concurrency[i].get();
        }
        if (total > cfg.concurrencyLimit) {
            // Overshot: rollback the increment and block
            st.concurrency[bidx].decrementAndGet();
            return -1;
        }

        return bidx;
    }

    public static void release(ResourceState st, int bucketIdx) {
        // AA Defect N3 fix: CAS-loop release that refuses to decrement below zero. A double-release
        // (caller bug) previously pushed the counter negative, silently corrupting the concurrency
        // limit (a resource could exceed its configured cap forever). Underflow is impossible on
        // the happy path — this only costs one extra get() per release.
        for (;;) {
            int cur = st.concurrency[bucketIdx].get();
            if (cur <= 0) {
                return; // defensive: double-release or stale segment — do not go negative
            }
            if (st.concurrency[bucketIdx].compareAndSet(cur, cur - 1)) {
                return;
            }
        }
    }

}
