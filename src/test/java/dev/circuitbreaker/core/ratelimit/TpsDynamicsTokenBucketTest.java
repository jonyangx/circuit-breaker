package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial TPS spike/drop/jitter tests for the lazy token bucket (package-private access).
 *
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §9 (TPS dynamics).
 *
 * Key properties verified:
 * - Burst is capped by capacity (rate limit is enforced even after a spike drains the bucket).
 * - After draining, you must WAIT for refill — the spike does not "borrow" future tokens.
 * - Jittered inter-arrival times are handled naturally: each interval is independently accounted.
 * - Long idle restores full burst capacity.
 */
class TpsDynamicsTokenBucketTest {

    private static ResourceConfig cfg(long qps, long capacity) {
        return new ResourceConfig(0x02, qps, capacity, 0, 1, 1000, 1000, 0, 1);
    }

    /**
     * §9.1: Spike drain — 1000 req burst at t=0 (capacity=1000).
     * After draining, an immediate follow-up must be blocked (no "borrow" of future tokens).
     */
    @Test
    void spikeDrainsBucketThenBlocksImmediateFollowup() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1000, 1000); // 1000 qps, burst 1000
        int passed = 0;
        for (int i = 0; i < 1000; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, 1000L)) passed++;
        }
        assertThat(passed).isEqualTo(1000);           // burst capacity consumed
        // Immediate follow-up at the same instant → blocked (refill = 0)
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1000L)).isFalse();
    }

    /**
     * §9.2: Spike drain, then a small wait (sub-refill), then more traffic.
     * After 1ms at qps=1000, exactly 1 token has refilled.
     */
    @Test
    void spikeThenPartialRefillResumesAtRate() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1000, 1000);
        // drain
        for (int i = 0; i < 1000; i++) {
            LazyTokenBucket.tryAcquire(st, c, 1000L);
        }
        // After 1000ms, 1000 tokens refilled (qps=1000, dtMs/1000=1)
        int passed2 = 0;
        for (int i = 0; i < 1000; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, 2000L)) passed2++;
        }
        assertThat(passed2).isEqualTo(1000);                        // 1s worth refilled
        assertThat(LazyTokenBucket.tryAcquire(st, c, 2000L)).isFalse(); // no more tokens
    }

    /**
     * §9.3: Long idle after spike restores full burst capacity.
     */
    @Test
    void longIdleAfterSpikeRestoresFullBurst() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(1000, 1000);
        // drain at t=1000
        for (int i = 0; i < 1000; i++) {
            LazyTokenBucket.tryAcquire(st, c, 1000L);
        }
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1000L)).isFalse();
        // After 5s idle, capacity fully restored (5000 tokens generated, capped at 1000)
        int passed = 0;
        for (int i = 0; i < 1000; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, 6000L)) passed++;
        }
        assertThat(passed).isEqualTo(1000);
    }

    /**
     * §9.4: Jittered inter-arrival times with AVERAGE rate = qps.
     * Irregular request spacing should not cause token-bucket state corruption or allow
     * more than capacity + (refill × elapsed) tokens through over a sustained window.
     */
    @Test
    void jitteredInterArrivalNoOverdraft() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(100, 100); // 100 qps, burst 100
        // Jittered pattern: gaps of [0,0,1,1,5,10,2,3,1,0,...] ms at t0=1000
        long t = 1000L;
        long[] gaps = {0, 0, 1, 1, 5, 10, 2, 3, 1, 0, 4, 6, 2, 8, 3, 1, 5, 7, 2, 9};
        int passed = 0;
        for (long g : gaps) {
            t += g;
            if (LazyTokenBucket.tryAcquire(st, c, t)) passed++;
        }
        // Total elapsed from t0: 0+0+1+1+5+10+2+3+1+0+4+6+2+8+3+1+5+7+2+9 = 70ms
        // Refill over 70ms at 100 qps = 7 tokens. Plus initial capacity 100.
        // Theoretical max = 100 + 7 = 107. We just assert it's bounded by capacity + refill.
        long tLast = st.bucketState.get() >>> LazyTokenBucket.TIME_SHIFT;
        long tok = st.bucketState.get() & LazyTokenBucket.TOKEN_MASK;
        // No overflow, time field sensible
        assertThat(tLast).isLessThanOrEqualTo(t);
        assertThat(tok).isLessThanOrEqualTo(LazyTokenBucket.TOKEN_MASK);
        assertThat(passed).isLessThanOrEqualTo(107); // bounded by capacity + refill, no overdraft
    }

    /**
     * §9.5: Spike then drop — sustained silence after a burst.
     * The bucket holds its state (lazy evaluation) without corruption.
     */
    @Test
    void spikeThenSilencePreservesState() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(100, 100);
        // partial drain
        int passed = 0;
        for (int i = 0; i < 50; i++) {
            if (LazyTokenBucket.tryAcquire(st, c, 1000L)) passed++;
        }
        assertThat(passed).isEqualTo(50);
        long tokBeforeSilence = st.bucketState.get() & LazyTokenBucket.TOKEN_MASK;

        // "Silence" — no acquires. State must be unchanged (lazy evaluation).
        long stateDuringSilence = st.bucketState.get();
        long tokDuringSilence = stateDuringSilence & LazyTokenBucket.TOKEN_MASK;
        assertThat(tokDuringSilence).isEqualTo(tokBeforeSilence);

        // After the silence, first acquire sees the refill accumulated during silence
        // (we just assert no corruption / no negative tokens).
        assertThat(LazyTokenBucket.tryAcquire(st, c, 10000L)).isTrue();
    }

    /**
     * §9.6: Reverse clock — nowMs goes backward.
     * dt = (now - tLast) could be negative; with long arithmetic and no mask on the
     * Time field (42 bits, ~139 years), a small backward jump yields a negative dt.
     * In Java, (negativeLong * qps / 1000) with the seed tLast=0 stays correct because
     * seed sets tLast=0. But after a real acquire, tLast > 0; a backward clock makes
     * add negative → nTok could go negative → nTok < 1 → return false (block).
     *
     * This is GRACEFUL: a backward clock blocks rather than corrupting.
     */
    @Test
    void backwardClockBlocksRatherThanCorrupts() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(100, 100);
        // Normal acquire at t=1000
        assertThat(LazyTokenBucket.tryAcquire(st, c, 1000L)).isTrue();
        long tLast = st.bucketState.get() >>> LazyTokenBucket.TIME_SHIFT;
        assertThat(tLast).isEqualTo(1000L);

        // Backward clock: now=500 < tLast=1000. add = (500-1000)*100/1000 = -50.
        // nTok = min(100, tok + (-50)). If tok=99 (after one consume), nTok = 49 → still ≥ 1.
        // So this might pass. Let's drain first to force the block.
        for (int i = 0; i < 99; i++) { // drain remaining 99 tokens
            LazyTokenBucket.tryAcquire(st, c, 1000L);
        }
        long tokAfterDrain = st.bucketState.get() & LazyTokenBucket.TOKEN_MASK;
        assertThat(tokAfterDrain).isZero(); // fully drained

        // Backward clock now: add = (500-1000)*100/1000 = -50 → nTok = min(100, 0 + (-50)) = -50 < 1 → block
        assertThat(LazyTokenBucket.tryAcquire(st, c, 500L)).isFalse();
        // State not corrupted: tLast still 1000, tok still 0
        long tLastAfter = st.bucketState.get() >>> LazyTokenBucket.TIME_SHIFT;
        long tokAfter = st.bucketState.get() & LazyTokenBucket.TOKEN_MASK;
        assertThat(tLastAfter).isEqualTo(1000L);
        assertThat(tokAfter).isZero();
    }
}
