package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent stress tests — validate the lock-free invariants (constitution 不变量3) under real
 * contention: no exceptions, no counter/concurrency drift, consistent pass+block totals.
 * Runs in CI (Linux) alongside the unit suite.
 */
class ConcurrencyStressTest {

    @Test
    void engineAllCapabilitiesUnderContentionNoDrift() throws InterruptedException {
        int rid = ResourceManager.register("stress-all",
                new ResourceConfig(0x07, 10_000_000, 10_000_000, 1_000_000, 1_000_000, 1000, 1000, 1_000_000, 1));
        ResourceState st = ResourceManager.state(rid);

        int threads = 8;
        int perThread = 5_000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        long token = FlatExecutionEngine.tryAcquire(rid);
                        if (token >= 0) {
                            FlatExecutionEngine.release(rid, token, (i & 1) == 0);
                        }
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
        es.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(error.get()).as("no exception under contention").isNull();
        long total = (long) threads * perThread;
        assertThat(st.passCount() + st.blockCount()).isEqualTo(total); // every attempt counted
        assertThat(st.sumConcurrency()).isZero();                       // every acquire released → no leak
    }

    @Test
    void breakerStateMachineNeverCorruptsUnderFailureLoad() throws InterruptedException {
        // breaker only (mask 0x01); error threshold reachable but timing-dependent under a tight
        // loop — the invariant we assert is robustness, not the trip itself.
        int rid = ResourceManager.register("stress-breaker",
                new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1));
        ResourceState st = ResourceManager.state(rid);

        int threads = 8;
        int perThread = 2_000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        long token = FlatExecutionEngine.tryAcquire(rid);
                        if (token >= 0) {
                            FlatExecutionEngine.release(rid, token, false); // report failure
                        }
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
        es.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(error.get()).as("breaker never throws under contention").isNull();
        long total = (long) threads * perThread;
        assertThat(st.passCount() + st.blockCount()).isEqualTo(total);
        // breakerState must remain a valid 2-bit state (CLOSED/OPEN/HALF_OPEN), no corruption.
        int s = (int) (st.breakerState.get() >>> 62);
        assertThat(s).isBetween(0, 2);
    }
}
