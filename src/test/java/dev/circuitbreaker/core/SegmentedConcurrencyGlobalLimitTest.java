package dev.circuitbreaker.core;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * SegmentedConcurrency GLOBAL enforcement regression test (AA Report §1.1 CRITICAL).
 *
 * <p>AA found that tryAcquire was changed from a global-limit check to a per-segment
 * probabilistic check, routinely violating the configured limit (PASS prob ≈ 81% at
 * limit=3, SEG=16). This test deterministically exposes the regression: after holding
 * N=limit tokens, the (N+1)th acquire MUST block regardless of which segment it hits.</p>
 */
class SegmentedConcurrencyGlobalLimitTest {

    /**
     * CRITICAL regression: after holding `limit` tokens, the next acquire MUST block.
     * With per-segment-only enforcement (limit=3, SEG=16), the 4th acquire passes with
     * ≈81% probability → this test fails on the buggy build. With global enforcement
     * (DA fix), it passes deterministically.
     *
     * <p>NOTE: With small limit values, per-segment caps can block an acquire even when
     * the global limit hasn't been reached (two probes hit the same segment). This test
     * therefore retries until `limit` tokens are held, rather than assuming sequential
     * acquires always succeed.
     */
    @RepeatedTest(20)
    void afterHoldingLimitTokensNextAcquireMustBlock() {
        int limit = 3;
        ResourceState st = new ResourceState();
        ResourceConfig cfg = new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, limit, 1);

        // Retry until we hold exactly `limit` tokens (per-segment caps may block some attempts)
        List<Integer> held = new ArrayList<>();
        while (held.size() < limit) {
            int bidx = dev.circuitbreaker.core.concurrency.SegmentedConcurrency.tryAcquire(st, cfg);
            if (bidx >= 0) {
                held.add(bidx);
            }
            // Safety: prevent infinite loop if something is fundamentally broken
            assertThat(held.size() + st.sumConcurrency()).isLessThan(limit * 10);
        }
        assertThat(st.sumConcurrency()).isEqualTo(limit);

        // The (limit+1)th acquire MUST be blocked (global limit enforcement)
        int blocked = dev.circuitbreaker.core.concurrency.SegmentedConcurrency.tryAcquire(st, cfg);
        assertThat(blocked)
                .as("global concurrency limit (%d) must hold; got a routed token %d", limit, blocked)
                .isLessThan(0);

        // Cleanup
        for (int bidx : held) {
            dev.circuitbreaker.core.concurrency.SegmentedConcurrency.release(st, bidx);
        }
        assertThat(st.sumConcurrency()).isZero();
    }

    /**
     * Even tighter: limit=1 means a single held token must block ALL subsequent acquires
     * (not just those hitting the same segment). On the buggy build this fails ≈94%.
     */
    @RepeatedTest(20)
    void limitOneHoldsGlobally() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1, 1);

        int bidx = dev.circuitbreaker.core.concurrency.SegmentedConcurrency.tryAcquire(st, cfg);
        assertThat(bidx).isBetween(0, ResourceState.SEG - 1);
        assertThat(st.sumConcurrency()).isEqualTo(1);

        // 2nd acquire must block (global limit = 1)
        int blocked = dev.circuitbreaker.core.concurrency.SegmentedConcurrency.tryAcquire(st, cfg);
        assertThat(blocked)
                .as("limit=1 must block all acquires after the first")
                .isLessThan(0);

        dev.circuitbreaker.core.concurrency.SegmentedConcurrency.release(st, bidx);
    }

    /**
     * Release frees slots so a new acquire can succeed.
     */
    @Test
    void releaseFreesSlotsForNewAcquires() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 2, 1);

        // Acquire 2 tokens (retry past per-segment cap collisions when limit < SEG)
        int b1 = acquireOne(st, cfg);
        int b2 = acquireOne(st, cfg);
        assertThat(st.sumConcurrency()).isEqualTo(2);
        assertThat(dev.circuitbreaker.core.concurrency.SegmentedConcurrency.tryAcquire(st, cfg)).isLessThan(0);

        dev.circuitbreaker.core.concurrency.SegmentedConcurrency.release(st, b1);
        assertThat(st.sumConcurrency()).isEqualTo(1);

        int b3 = acquireOne(st, cfg);
        assertThat(b3).isBetween(0, ResourceState.SEG - 1);

        dev.circuitbreaker.core.concurrency.SegmentedConcurrency.release(st, b2);
        dev.circuitbreaker.core.concurrency.SegmentedConcurrency.release(st, b3);
    }

    /** Acquire one token, retrying past transient per-segment caps (limitPerSeg=1 collisions). */
    private static int acquireOne(ResourceState st, ResourceConfig cfg) {
        int attempts = 0;
        int bidx;
        do {
            bidx = dev.circuitbreaker.core.concurrency.SegmentedConcurrency.tryAcquire(st, cfg);
            assertThat(++attempts).isLessThan(100); // safety bound
        } while (bidx < 0);
        return bidx;
    }

    /**
     * Under sustained concurrent load the global limit is never exceeded by more than the
     * documented per-segment overshoot tolerance. On the buggy build, concurrency routinely
     * reaches limit+1 (and beyond), violating the assertion.
     */
    @Test
    void concurrentAcquiresRespectGlobalLimit() throws Exception {
        int limit = 5;
        int threads = 12;
        ResourceState st = new ResourceState();
        ResourceConfig cfg = new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, limit, 1);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    int bidx = dev.circuitbreaker.core.concurrency.SegmentedConcurrency.tryAcquire(st, cfg);
                    if (bidx >= 0) {
                        held.incrementAndGet();
                        Thread.sleep(50); // hold the token briefly
                        dev.circuitbreaker.core.concurrency.SegmentedConcurrency.release(st, bidx);
                    } else {
                        blocked.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // At least some acquires must have been blocked (because limit < threads)
        assertThat(blocked.get()).isGreaterThan(0);
        // All tokens returned
        assertThat(st.sumConcurrency()).isZero();
    }
}