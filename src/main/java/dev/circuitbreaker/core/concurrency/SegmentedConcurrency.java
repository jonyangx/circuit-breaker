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
     *   3. Increment the probed segment (optimistic, no CAS)
     *   4. Compute total concurrency (segment sum, O(16) lock-free read)
     *   5. Global limit check: if total > concurrencyLimit, rollback (decrement) and block
     *
     * Why increment-then-check (not check-then-increment)?
     *   - Pre-check on total is racy (another thread may increment concurrently)
     *   - Increment-then-check is exact: we see the post-increment total and enforce the limit atomically
     *   - Rollback on overflow is O(1) and rare (only when limit is tight)
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
        st.concurrency[bucketIdx].decrementAndGet();
    }
}
