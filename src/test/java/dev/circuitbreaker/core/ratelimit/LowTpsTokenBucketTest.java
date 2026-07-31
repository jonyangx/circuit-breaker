package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Low-TPS tests for the lazy token bucket (package-private access). Aligned with
 * docs/system/07_ALGORITHM_DEEP_DIVE.md §6.1.
 */
class LowTpsTokenBucketTest {

    private static ResourceConfig cfg(long qps, long capacity) {
        return new ResourceConfig(0x02, qps, capacity, 0, 1, 1000, 1000, 0, 1);
    }

    /** §6.1: qps=1 — sub-second intervals yield no token AND tLast does not advance (BR-013). */
    @Test
    void subSecondNoTokenAndTlastNotAdvanced() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1, 1);
        assertThat(LazyTokenBucket.tryAcquire(st, c, 500L)).isFalse();
        assertThat(LazyTokenBucket.tryAcquire(st, c, 999L)).isFalse();
        // tLast must stay at 0 (the seed/default) — BR-013 float-zero-out protection
        assertThat(st.bucketState.get() >>> LazyTokenBucket.TIME_SHIFT).isZero();
    }

    /** §6.1: qps=1 — at exactly 1000ms, exactly 1 token becomes available. */
    @Test
    void exactlyOneSecondOneToken() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1, 1);
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1000L)).isTrue();
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1000L)).isFalse(); // consumed
    }

    /** §6.1: qps=1, capacity=1 — 10s idle accumulates only 1 token (burst cap). */
    @Test
    void longIdleBurstCapped() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1, 1);
        assertThat(LazyTokenBucket.tryAcquire(st, c, 10000L)).isTrue();
        assertThat(LazyTokenBucket.tryAcquire(st, c, 10000L)).isFalse();
    }

    /** §6.1: qps=1, capacity=5 — long idle fills to capacity, not to add count. */
    @Test
    void longIdleFillsToCapacity() {
        ResourceState st = new ResourceState();
        LazyTokenBucket.seed(st, 5);
        ResourceConfig c = cfg(1, 5);
        int passed = 0;
        for (int i = 0; i < 10; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, 10000L)) passed++;
        }
        assertThat(passed).isEqualTo(5); // capacity cap, not 10
    }

    /** §6.1: qps=1, then idle and return — cumulative refill works across sparse calls. */
    @Test
    void sparseCallsAccumulate() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1, 5);
        // t=0 → t=500 nothing
        assertThat(LazyTokenBucket.tryAcquire(st, c, 500L)).isFalse();
        // t=1500 (1000ms of accumulation, 1 token)
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1500L)).isTrue();
        // t=1500 consumed, t=2500 (1000ms more → 1 token)
        assertThat(LazyTokenBucket.tryAcquire(st, c, 2500L)).isTrue();
        // t=4500 (2000ms idle → 2 tokens, capped at capacity=5)
        assertThat(LazyTokenBucket.tryAcquire(st, c, 4500L)).isTrue();
        assertThat(LazyTokenBucket.tryAcquire(st, c, 4500L)).isTrue();
        // out of tokens
        assertThat(LazyTokenBucket.tryAcquire(st, c, 4500L)).isFalse();
    }
}
