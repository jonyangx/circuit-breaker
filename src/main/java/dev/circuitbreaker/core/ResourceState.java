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
 * <b>The JVM ignores {@code @Contended} on user classes unless {@code -XX:-RestrictContended} is set</b>
 * ({@code RestrictContended} defaults to {@code true}). Measured on JDK 21: without the flag the three
 * hot AtomicLongs sit at offsets 12/16/20 (4B apart, shared cache lines); with it 280/412/544 (cache-line
 * isolated). So the embedding JVM MUST launch with both:
 * {@code --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED} (compile/reference) and
 * {@code -XX:-RestrictContended} (runtime layout). Enforced in-tests by {@code ContendedPaddingGuardTest}.</p>
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

    /**
     * P1 fix: probe generation to prevent stale releases from hijacking HALF_OPEN transition.
     * Tracks the breaker generation of the current probe (set on OPEN→HALF_OPEN transition).
     * Only a release whose breaker generation matches probeGen can transition HALF_OPEN state.
     * Initialized to -1 (no probe elected).
     */
    public final AtomicLong probeGen = new AtomicLong(-1L);

    /**
     * R1 fix (AA residual): absolute-ms wall anchor of the last committed EWMA update, used by
     * {@code EwmaCircuitBreaker.updateEwma} to detect long idle even when the 20-bit modular
     * {@code lastUpdateMs} field aliases the gap to ~0 (a periodic idle whose true gap is
     * {@code k·2²⁴ ms + ε} presents dt≈0 and would otherwise slip past the modular re-seed guard,
     * freezing stale error rate → false trip on the first recovery sample). nowRelMs() is
     * nanoTime-based (~292y wrap), so this absolute difference is exact. NOT {@code @Contended}:
     * touched only on the release path of breaker-governed calls, never per acquire.
     * -1 = no update ever committed (first update falls back to the modular guard).
     */
    public final AtomicLong lastEwmaUpdateMs = new AtomicLong(-1L);

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

    /**
     * Time-decay EWMA error rate in parts-per-million.
     * @return error rate in [0, 1_000_000] ppm (i.e. 0..100%); 0xFFFFF mask = 20-bit field.
     */
    public int ewmaErrorRatePpm() {
        return (int) (ewmaState.get() & 0xFFFFFL);
    }
}
