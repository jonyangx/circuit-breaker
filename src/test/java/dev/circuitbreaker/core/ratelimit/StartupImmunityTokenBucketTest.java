package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup-immunity tests for the lazy token bucket (uses package-private members).
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §5.3.
 */
class StartupImmunityTokenBucketTest {

    private static ResourceConfig cfg(long qps, long capacity) {
        return new ResourceConfig(0x02, qps, capacity, 0, 1, 1000, 1000, 0, 1);
    }

    /** §5.3: seed() sets the bucket to full capacity → burst is available immediately on first acquire. */
    @Test
    void startupSeedMakesBurstAvailableImmediately() {
        ResourceState st = new ResourceState();
        LazyTokenBucket.seed(st, 100);
        // first acquire right after seed must pass (no warm-up delay)
        assertThat(LazyTokenBucket.tryAcquire(st, cfg(100, 100), 1000L)).isTrue();
    }

    /** §5.3: seed() caps an oversized capacity to TOKEN_MASK (no overflow into Time field). */
    @Test
    void startupSeedCapsOversizedCapacity() {
        ResourceState st = new ResourceState();
        LazyTokenBucket.seed(st, 10_000_000L); // > TOKEN_MASK (~4.19M)
        long cur = st.bucketState.get();
        long tok = cur & LazyTokenBucket.TOKEN_MASK;
        long tLast = cur >>> LazyTokenBucket.TIME_SHIFT;
        assertThat(tok).isLessThanOrEqualTo(LazyTokenBucket.TOKEN_MASK); // capped, not overflowed
        assertThat(tLast).isZero(); // Time field untouched
    }

    /** §5.1: starting from any clock offset, the bucket behaves the same (relative Δt only). */
    @Test
    void startupClockOffsetIrrelevantToRefill() {
        // qps=1, capacity=1. After 1000ms, exactly 1 token should be available regardless of absolute time.
        ResourceConfig c = cfg(1, 1);
        ResourceState st1 = new ResourceState();
        ResourceState st2 = new ResourceState();
        // st1: started at t=0, used at t=1000
        assertThat(LazyTokenBucket.tryAcquire(st1, c, 1000L)).isTrue();
        // st2: "started" at a large offset, used 1000ms later
        assertThat(LazyTokenBucket.tryAcquire(st2, c, 1_000_000L)).isTrue();
        // both consumed exactly 1 token (seeded capacity=1)
        long tok1 = st1.bucketState.get() & LazyTokenBucket.TOKEN_MASK;
        long tok2 = st2.bucketState.get() & LazyTokenBucket.TOKEN_MASK;
        assertThat(tok1).isEqualTo(tok2);
    }

    /** §5.3: a fresh (un-seeded) bucket at tLast=0 refills to capacity on first acquire. */
    @Test
    void startupUnseededBucketRefillsFromZeroOnFirstAcquire() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1000, 1000);
        // no seed; tLast=0, tok=0. At now=1000, add = (1000-0)*1000/1000 = 1000 → full capacity
        int passed = 0;
        for (int i = 0; i < 1000; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, 1000L)) passed++;
        }
        assertThat(passed).isEqualTo(1000); // refilled to capacity on first acquire
    }
}
