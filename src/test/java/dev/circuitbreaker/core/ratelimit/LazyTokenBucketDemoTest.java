package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

/**
 * Walk-through demo of the lazy token bucket. A fixed time series is injected (nowMs is a
 * parameter), and each acquire prints the unpacked state so you can see exactly how Time_last
 * and Tokens evolve. qps=10, capacity=10.
 *
 * <p>Run with: {@code ./gradlew test --tests "...LazyTokenBucketDemoTest" --info}</p>
 */
class LazyTokenBucketDemoTest {

    private static ResourceConfig cfg(long qps, long capacity) {
        return new ResourceConfig(0x02, qps, capacity, 0, 1, 1000, 1000, 0, 1);
    }

    private static void call(ResourceState st, ResourceConfig c, long now, String label) {
        long before = st.bucketState.get();
        long tLast0 = before >>> LazyTokenBucket.TIME_SHIFT;
        long tok0 = before & LazyTokenBucket.TOKEN_MASK;
        boolean pass = LazyTokenBucket.tryAcquire(st, c, now);
        long after = st.bucketState.get();
        long tLast1 = after >>> LazyTokenBucket.TIME_SHIFT;
        long tok1 = after & LazyTokenBucket.TOKEN_MASK;
        long dt = now - tLast0;
        long add = dt * c.qps / 1000L; // N1: ms-granularity refill (matches implementation)
        System.out.printf("%-12s now=%-6d dt=%-5d add=%-3d tok:%-3d->%-3d tLast:%-6d->%-6d %s%n",
                label, now, dt, add, tok0, tok1, tLast0, tLast1, pass ? "PASS" : "BLOCK");
    }

    @Test
    void walkThrough() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg(10, 10);          // 10 tokens/sec, burst 10
        LazyTokenBucket.seed(st, 10);            // initial full bucket: tLast=0, tok=10
        System.out.println("seed  -> tLast=0, tok=10 (full capacity)\n");

        // Phase B: a burst of 12 calls all at t=500ms (now is identical for all)
        for (int i = 1; i <= 12; i++) {
            call(st, c, 500, "burst#" + i);
        }
        // Phase C: retry at t=800ms — only 300ms since tLast=500, sub-second
        call(st, c, 800, "t=800");
        // Phase D: t=1500ms — a full second elapsed since tLast=500 → refill
        call(st, c, 1500, "t=1500");
        // Phase E: two more calls right after the refill
        call(st, c, 1500, "post#1");
        call(st, c, 1500, "post#2");
    }
}
