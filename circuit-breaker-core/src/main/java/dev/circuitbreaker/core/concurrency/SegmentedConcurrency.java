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

    /** @return >=0 routed segment index (written into token); <0 if blocked (caller maps to -4). */
    public static int tryAcquire(ResourceState st, ResourceConfig cfg) {
        int bidx = ThreadLocalRandom.current().nextInt(ResourceState.SEG);
        long sum = 0;
        for (int i = 0; i < ResourceState.SEG; i++) {
            sum += st.concurrency[i].get();
        }
        if (sum >= cfg.concurrencyLimit) {
            return -1;
        }
        st.concurrency[bidx].incrementAndGet();
        return bidx;
    }

    public static void release(ResourceState st, int bucketIdx) {
        st.concurrency[bucketIdx].decrementAndGet();
    }
}
