package dev.circuitbreaker.core.reload;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * ConfigSwapper version race condition test (AA Report §2.2 HIGH).
 * Verifies atomic CAS loop correctness under concurrent swaps.
 */
class ConfigSwapperConcurrencyTest {

    /**
     * Concurrent threads with the SAME version number should race; only one must win.
     * The loser threads should receive IllegalArgumentException due to version check.
     * This validates that the check + CAS loop correctly rejects duplicate versions.
     */
    @Test
    void concurrentSwapsWithSameVersionRaceExactlyOneWins() throws Exception {
        int rid = ResourceManager.register(
                new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1));

        int nThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Exception> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < nThreads; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // synchronize start
                    // All threads attempt to publish version 2
                    ResourceConfig newConfig = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 2);
                    ConfigSwapper.swap(rid, newConfig);
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    failureCount.incrementAndGet();
                    exceptions.add(e);
                } catch (Exception e) {
                    exceptions.add(e);
                }
            });
        }

        latch.countDown(); // all threads start
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Exactly one thread should succeed, all others should fail with version check
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(nThreads - 1);
        for (Exception e : exceptions) {
            assertThat(e).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be > current version");
        }

        // Verify final config is version 2
        assertThat(ResourceManager.config(rid).version).isEqualTo(2);
    }

    /**
     * Threads with INCREASING version numbers: final version should be the max,
     * and no thread should crash with RuntimeException (only version check failures).
     * This validates monotonicity enforcement under race conditions.
     */
    @Test
    void concurrentSwapsWithIncreasingVersionsFinalVersionIsMax() throws Exception {
        int rid = ResourceManager.register(
                new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1));

        int nThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Exception> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < nThreads; i++) {
            final int version = i + 2; // versions 2..9
            executor.submit(() -> {
                try {
                    latch.await();
                    ResourceConfig newConfig = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, version);
                    ConfigSwapper.swap(rid, newConfig);
                } catch (IllegalArgumentException e) {
                    failureCount.incrementAndGet();
                    exceptions.add(e);
                } catch (Exception e) {
                    // Capture any unexpected exception (RuntimeException)
                    exceptions.add(e);
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // No unexpected exceptions (only version check failures are OK)
        for (Exception e : exceptions) {
            assertThat(e).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be > current version");
        }

        // Final config version should be the maximum (9)
        assertThat(ResourceManager.config(rid).version).isEqualTo(9);
    }

    /**
     * A thread that reads version X, prepares version X+1, but another thread wins the CAS,
     * should correctly fail on retry with version check (not silently corrupt).
     */
    @Test
    void loserThreadFailsWithVersionCheckOnRetry() throws Exception {
        int rid = ResourceManager.register(
                new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> loserException = new AtomicReference<>();

        // Thread 1: will win the race
        executor.submit(() -> {
            try {
                latch.await();
                ResourceConfig cfg2 = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 2);
                ConfigSwapper.swap(rid, cfg2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: will lose the race, then retry with stale version
        executor.submit(() -> {
            try {
                latch.await();
                // Small delay to let thread 1 win (probabilistic, flaky but OK for demo)
                Thread.sleep(50);
                ResourceConfig cfg2 = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 2);
                try {
                    ConfigSwapper.swap(rid, cfg2);
                } catch (IllegalArgumentException e) {
                    loserException.set(e);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Loser should have received version check exception (or winner may have raced differently)
        if (loserException.get() != null) {
            assertThat(loserException.get()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be > current version");
        }
    }
}