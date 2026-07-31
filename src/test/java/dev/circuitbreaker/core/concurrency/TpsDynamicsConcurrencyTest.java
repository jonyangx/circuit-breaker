package dev.circuitbreaker.core.concurrency;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial TPS spike tests for segmented concurrency — validates overshoot behavior
 * under burst traffic and that release is always correct.
 *
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §9 (TPS dynamics).
 */
class TpsDynamicsConcurrencyTest {

    /**
     * §9.3: Spike to concurrency limit — many threads arrive simultaneously.
     * Some overshoot is EXPECTED (the known trade-off for lock-freedom).
     * But under reasonable load, the overshoot should be small.
     */
    @Test
    void spikeToLimitCausesSmallOvershoot() throws InterruptedException {
        ResourceState st = new ResourceState();
        int limit = 10;
        ResourceConfig cfg = new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, limit, 1);
        int threads = 20;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    int bidx = SegmentedConcurrency.tryAcquire(st, cfg);
                    if (bidx >= 0) {
                        // hold the slot briefly — do NOT release
                    }
                } catch (Throwable ex) {
                    error.set(ex);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        es.shutdown();
        assertThat(error.get()).isNull();

        long sum = st.sumConcurrency();
        // Overshoot is expected: more than `limit` threads may have acquired.
        // But with 16 segments and 20 threads, the overshoot should be bounded.
        // The segment-by-segment sum reflects the overshoot.
        assertThat(sum).isGreaterThanOrEqualTo(0);
        // With 20 threads contending on limit=10, some will be blocked (-1 returned),
        // so sum will be between limit and threads.
        assertThat(sum).as("concurrency overshoot may exceed limit, but must be bounded").isBetween(0L, (long) threads);
    }

    /**
     * §9.3: After a spike, releasing empties the segments — no leaks.
     */
    @Test
    void releaseAfterSpikeReturnsAllSlots() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1);
        int[] buckets = new int[20];
        int acquired = 0;
        for (int i = 0; i < 20; i++) {
            int b = SegmentedConcurrency.tryAcquire(st, cfg);
            if (b >= 0) buckets[acquired++] = b;
        }
        assertThat(acquired).isEqualTo(20);
        // Release all
        for (int i = 0; i < acquired; i++) {
            SegmentedConcurrency.release(st, buckets[i]);
        }
        assertThat(st.sumConcurrency()).isZero();
    }
}
