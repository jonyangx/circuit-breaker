package dev.circuitbreaker.core;

import jdk.internal.vm.annotation.Contended;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Resource runtime state (aggregate root, stable across config versions — BR-002/051).
 * Never rebuilt on rule change; in-flight release always lands on the correct counters.
 *
 * <p>Hot-path atomic fields carry {@link Contended} padding to prevent false sharing.
 * Requires JVM flag {@code --add-exports=jdk.unsupported/jdk.internal.vm.annotation=ALL-UNNAMED}</p>
 */
@Contended
public final class ResourceState {
    public static final int SEG = 16;
    @Contended public final AtomicLong bucketState = new AtomicLong();
    @Contended public final AtomicLong breakerState = new AtomicLong();
    @Contended public final AtomicLong ewmaState = new AtomicLong();
    public final AtomicInteger[] concurrency = new AtomicInteger[SEG];
    public final LongAdder passCount = new LongAdder();
    public final LongAdder blockCount = new LongAdder();

    public ResourceState() {
        for (int i = 0; i < SEG; i++) {
            concurrency[i] = new AtomicInteger();
        }
    }

    public long sumConcurrency() {
        long s = 0;
        for (AtomicInteger a : concurrency) {
            s += a.get();
        }
        return s;
    }

    public long passCount() { return passCount.sum(); }
    public long blockCount() { return blockCount.sum(); }

    public int ewmaErrorRatePpm() {
        return (int) (ewmaState.get() & 0xFFFFFL);
    }
}
