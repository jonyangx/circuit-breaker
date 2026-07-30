package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** LazyTokenBucket tests (UC-004; BR-010/011/012/013). TC-CAP-RL-001..004. */
class LazyTokenBucketTest {

    private static ResourceConfig cfg(long qps, long capacity) {
        return new ResourceConfig(0x02, qps, capacity, 0, 1, 1000, 1000, 0, 1);
    }

    @Test
    void refillsAndEnforcesRate() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1000, 1000); // 1000 token/sec, burst 1000
        long now = 1000L;
        // first call refills from tLast=0 → up to capacity
        int passed = 0;
        for (int i = 0; i < 1000; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, now)) passed++;
        }
        assertThat(passed).isEqualTo(1000);            // burst consumed
        assertThat(LazyTokenBucket.tryAcquire(st, c, now)).isFalse(); // over rate → block
        assertThat(LazyTokenBucket.tryAcquire(st, c, now + 1)).isTrue(); // +1ms → +1 token
    }

    @Test
    void capacityCapped() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(10_000, 10); // huge rate, burst 10
        int passed = 0;
        for (int i = 0; i < 100; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, 1000L)) passed++;
        }
        assertThat(passed).isEqualTo(10);              // capped at capacity (BR-011 min)
    }

    @Test
    void lowQpsNoFloatZeroOut() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1, 1); // 1 token/sec
        // sub-second intervals never yield a token (BR-013: do not advance tLast)
        assertThat(LazyTokenBucket.tryAcquire(st, c, 500L)).isFalse();
        assertThat(LazyTokenBucket.tryAcquire(st, c, 999L)).isFalse();
        // only once a full second elapsed does a token appear
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1000L)).isTrue();
    }

    @Test
    void capacityAboveFieldMaxIsCappedNotCorrupted() {
        // B1: capacity/qps > 2^22-1 must be capped, not overflow the 22-bit token field into Time.
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(10_000_000, 10_000_000); // > TOKEN_MASK (~4.19M), constructed directly
        LazyTokenBucket.seed(st, 10_000_000);
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1_000_000L)).isTrue();
        long cur = st.bucketState.get();
        long tok = cur & LazyTokenBucket.TOKEN_MASK;
        long tLast = cur >>> LazyTokenBucket.TIME_SHIFT;
        assertThat(tok).isLessThanOrEqualTo(LazyTokenBucket.TOKEN_MASK); // no overflow
        assertThat(tLast).isEqualTo(1_000_000L);                          // Time field not corrupted
    }
}
