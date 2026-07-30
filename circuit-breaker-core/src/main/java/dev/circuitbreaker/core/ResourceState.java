package dev.circuitbreaker.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Resource runtime state (aggregate root, stable across config versions — BR-002/051).
 * Never rebuilt on rule change; in-flight release always lands on the correct counters.
 */
public final class ResourceState {
    public static final int SEG = 16;
    // public final: references are published so engine/capability classes in sub-packages can
    // mutate via the atomic APIs; thread-safe mutation is enforced by the atomic objects themselves.
    public final AtomicLong bucketState  = new AtomicLong();
    public final AtomicLong breakerState = new AtomicLong();
    public final AtomicLong ewmaState    = new AtomicLong();
    public final AtomicInteger[] concurrency = new AtomicInteger[SEG];
    public final LongAdder passCount  = new LongAdder();
    public final LongAdder blockCount = new LongAdder();

    public ResourceState() {
        for (int i = 0; i < SEG; i++) {
            concurrency[i] = new AtomicInteger();
        }
    }

    /** Sum of segments (approximate concurrency; for tests/export, off hot path). */
    public long sumConcurrency() {
        long s = 0;
        for (AtomicInteger a : concurrency) {
            s += a.get();
        }
        return s;
    }

    public long passCount() { return passCount.sum(); }
    public long blockCount() { return blockCount.sum(); }

    /** Current EWMA error rate in ppm (low 20 bits of ewmaState). Read-only, for observability. */
    public int ewmaErrorRatePpm() {
        return (int) (ewmaState.get() & 0xFFFFFL);
    }
}
