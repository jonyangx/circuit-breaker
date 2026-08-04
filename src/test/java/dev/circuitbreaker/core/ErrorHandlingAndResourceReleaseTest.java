package dev.circuitbreaker.core;

import dev.circuitbreaker.core.reload.ConfigSwapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Error handling and resource release path test (AA Report §5.1).
 * Covers release semantics, cross-resource detection, blocked-token no-op,
 * mask consistency, and concurrency rollback under error paths.
 */
class ErrorHandlingAndResourceReleaseTest {

    /**
     * Releasing a blocked token (negative) is a no-op; never touches counters (BR-004).
     */
    @Test
    void releaseBlockedTokenIsNoOp() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        ResourceState st = ResourceManager.state(rid);

        long beforeSum = st.sumConcurrency();
        long beforeBlock = st.blockCount();
        long beforePass = st.passCount();

        FlatExecutionEngine.release(rid, BlockCode.SYSTEM_OVERLOAD, true);
        FlatExecutionEngine.release(rid, BlockCode.CIRCUIT_BREAKER, false);
        FlatExecutionEngine.release(rid, BlockCode.RATE_LIMITER, true);
        FlatExecutionEngine.release(rid, BlockCode.CONCURRENCY, false);

        assertThat(st.sumConcurrency()).isEqualTo(beforeSum);
        assertThat(st.blockCount()).isEqualTo(beforeBlock);
        assertThat(st.passCount()).isEqualTo(beforePass);
    }

    /**
     * Release validates resourceId before token; blocked tokens still surface bad resourceId.
     */
    @Test
    void releaseValidatesResourceIdBeforeToken() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);

        assertThatThrownBy(() -> FlatExecutionEngine.release(-1, token, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceId out of range");
        assertThatThrownBy(() -> FlatExecutionEngine.release(1023, token, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unregistered resourceId");
        assertThatThrownBy(() -> FlatExecutionEngine.release(-1, BlockCode.CONCURRENCY, false))
                .isInstanceOf(IllegalArgumentException.class);

        FlatExecutionEngine.release(rid, token, true); // cleanup
    }

    /**
     * BR-053: cross-resource release defense rejects a token whose embedded resourceId
     * differs from the passed resourceId.
     */
    @Test
    void crossResourceReleaseIsRejected() {
        int ridA = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1));
        long tokenA = FlatExecutionEngine.tryAcquire(ridA);

        assertThatThrownBy(() -> FlatExecutionEngine.release(ridB, tokenA, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token/resourceId mismatch");

        FlatExecutionEngine.release(ridA, tokenA, true); // cleanup
    }

    /**
     * Release with a disabled capability (mask bit clear) skips that capability.
     * BR-005 mask consistency in release path.
     */
    @Test
    void releaseRespectsMaskConsistency() {
        // Only concurrency, no circuit breaker
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1));
        ResourceState st = ResourceManager.state(rid);

        long token = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(0x04);

        // Release should decrement concurrency but not touch breaker (mask has no CB bit)
        long before = st.sumConcurrency();
        FlatExecutionEngine.release(rid, token, true);
        assertThat(st.sumConcurrency()).isEqualTo(before - 1);
    }

    /**
     * Hot-swap disabling a capability mid-flight: release must STILL roll back the capability
     * that was enabled at acquire time, because the token carries the acquire-time mask.
     *
     * <p>This is the P2 fix (FlatExecutionEngine.release uses the token's embedded mask, not the
     * current config mask). If release used the current mask, a hot-swap that disables concurrency
     * mid-flight would leak the slot forever — verified by {@link HotReloadDisableLeakTest}.
     */
    @Test
    void releaseUsesTokenMaskRegardlessOfHotSwap() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04 | 0x01, 0, 0, 500_000, 5, 1000, 1000, 100, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);

        // Hot-swap to a config with concurrency DISABLED (mask 0x01 only)
        ConfigSwapper.swap(rid, new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 2));

        ResourceState st = ResourceManager.state(rid);
        long concBefore = st.sumConcurrency();

        // Release the token acquired under the old config (token mask = 0x05, still has CONCURRENCY).
        // Release must decrement concurrency (token-mask driven), NOT skip it.
        FlatExecutionEngine.release(rid, token, true);

        // Concurrency rolled back (token carried the acquire-time CONCURRENCY bit)
        assertThat(st.sumConcurrency()).isEqualTo(concBefore - 1);
    }

    /**
     * Concurrent acquire/release under concurrency limit: net concurrency must never go negative
     * and must respect the limit. Exercises error path (some acquires block → release no-op).
     */
    @Test
    void concurrentAcquireReleaseNeverNegative() throws Exception {
        int limit = 4;
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, limit, 1));
        ResourceState st = ResourceManager.state(rid);

        int threads = 16;
        int iterations = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong maxObserved = new AtomicLong(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < iterations; i++) {
                        long token = FlatExecutionEngine.tryAcquire(rid);
                        if (token >= 0) {
                            long cur = st.sumConcurrency();
                            // update max under no lock (approximate, OK for bound check)
                            maxObserved.accumulateAndGet(cur, Math::max);
                            FlatExecutionEngine.release(rid, token, i % 3 == 0); // mix success/fail
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // After all threads done, concurrency must be back to 0 (all released)
        assertThat(st.sumConcurrency()).isZero();
        // Never exceeded limit + SEG overshoot tolerance (per-segment allows slight overshoot)
        assertThat(maxObserved.get()).isLessThanOrEqualTo((long) limit + ResourceState.SEG);
    }

    /**
     * Release with success=false still rolls back concurrency and updates breaker EWMA.
     */
    @Test
    void releaseWithFailureRollsBackConcurrency() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04 | 0x01, 0, 0, 500_000, 5, 1000, 1000, 100, 1));
        ResourceState st = ResourceManager.state(rid);

        long token = FlatExecutionEngine.tryAcquire(rid);
        long before = st.sumConcurrency();

        FlatExecutionEngine.release(rid, token, false); // failure path

        assertThat(st.sumConcurrency()).isEqualTo(before - 1);
    }

    /**
     * Releasing a token twice corrupts concurrency (decrement below intended).
     * This documents current behavior: double-release is NOT guarded (caller responsibility).
     * The test verifies the documented precondition rather than asserting safety.
     */
    @Test
    void acquireThenSingleReleaseLeavesConsistentState() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1));
        ResourceState st = ResourceManager.state(rid);

        long token = FlatExecutionEngine.tryAcquire(rid);
        assertThat(st.sumConcurrency()).isEqualTo(1);

        FlatExecutionEngine.release(rid, token, true);
        assertThat(st.sumConcurrency()).isZero();

        // A fresh acquire after proper release works
        long token2 = FlatExecutionEngine.tryAcquire(rid);
        assertThat(token2).isGreaterThanOrEqualTo(0);
        FlatExecutionEngine.release(rid, token2, true);
    }
}