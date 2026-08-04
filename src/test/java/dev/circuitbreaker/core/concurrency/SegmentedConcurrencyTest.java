package dev.circuitbreaker.core.concurrency;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** SegmentedConcurrency tests (UC-006; BR-030/031/032). TC-CAP-CC-001..003, TC-API-003-002. */
class SegmentedConcurrencyTest {

    private static ResourceConfig cfg(int limit) {
        return new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, limit, 1);
    }

    @Test
    void blocksOverLimitAndRollsBack() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(4);
        // With limit=4 < SEG=16, limitPerSeg=1, so a transient per-segment cap can block an
        // acquire before the global limit is reached. Retry until 4 slots are held.
        int[] bidx = acquireN(st, c, 4);
        assertThat(SegmentedConcurrency.tryAcquire(st, c)).isLessThan(0); // 5th blocked (global limit)
        for (int i = 0; i < 4; i++) {
            SegmentedConcurrency.release(st, bidx[i]);
        }
        assertThat(st.sumConcurrency()).isZero(); // rollback to zero
    }

    /** Acquire exactly n tokens, retrying past transient per-segment caps (limitPerSeg=1 collisions). */
    private static int[] acquireN(ResourceState st, ResourceConfig c, int n) {
        int[] bidx = new int[n];
        int got = 0, attempts = 0;
        while (got < n) {
            int b = SegmentedConcurrency.tryAcquire(st, c);
            if (b >= 0) {
                bidx[got++] = b;
            }
            assertThat(++attempts).isLessThan(n * 100); // safety bound
        }
        return bidx;
    }

    @Test
    void crossThreadReleaseZeroDrift() throws InterruptedException {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1_000_000); // effectively no block, exercise rollback
        int threads = 8, perThread = 2_000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    int bidx = SegmentedConcurrency.tryAcquire(st, c);
                    if (bidx >= 0) {
                        acquired.incrementAndGet();
                        SegmentedConcurrency.release(st, bidx); // release possibly on another thread's segment? no — same bidx
                    }
                }
                latch.countDown();
            });
        }
        latch.await();
        es.shutdown();
        es.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(acquired.get()).isEqualTo(threads * perThread);
        assertThat(st.sumConcurrency()).isZero(); // every acquire rolled back → zero drift (BR-032)
    }

    @Test
    void doubleReleaseDoesNotUnderflow() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1_000_000);
        int bidx = SegmentedConcurrency.tryAcquire(st, c);
        assertThat(bidx).isGreaterThanOrEqualTo(0);

        SegmentedConcurrency.release(st, bidx);
        SegmentedConcurrency.release(st, bidx); // caller bug: double release of the same token

        // CAS-guard release refuses to decrement below zero — a negative counter would silently
        // expand the effective concurrency limit (resource could exceed its configured cap forever).
        assertThat(st.concurrency[bidx].get()).isZero();

        // The segment still works for subsequent traffic.
        int bidx2 = SegmentedConcurrency.tryAcquire(st, c);
        assertThat(bidx2).isGreaterThanOrEqualTo(0);
        SegmentedConcurrency.release(st, bidx2);
        assertThat(st.sumConcurrency()).isZero();
    }
}
