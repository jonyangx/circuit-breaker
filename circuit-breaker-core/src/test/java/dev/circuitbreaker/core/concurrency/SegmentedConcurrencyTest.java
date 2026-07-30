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
        int[] bidx = new int[4];
        for (int i = 0; i < 4; i++) {
            bidx[i] = SegmentedConcurrency.tryAcquire(st, c);
            assertThat(bidx[i]).isBetween(0, ResourceState.SEG - 1);
        }
        assertThat(SegmentedConcurrency.tryAcquire(st, c)).isLessThan(0); // 5th blocked
        for (int i = 0; i < 4; i++) {
            SegmentedConcurrency.release(st, bidx[i]);
        }
        assertThat(st.sumConcurrency()).isZero(); // rollback to zero
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
}
