package dev.circuitbreaker.benchmarks;

import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmark: verifies SC-001 (ns-level) and SC-002 (zero allocation).
 * Run with: ./gradlew :circuit-breaker-benchmarks:jmh   (after enabling a JMH plugin)
 *           -prof gc   to confirm zero allocation on the hot path.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class AcquireReleaseBenchmark {

    private int resourceId;

    @Setup
    public void setup() {
        resourceId = ResourceManager.register(
                new ResourceConfig(0x07, 1_000_000, 1_000_000, 1_000_000, 1_000, 1000, 1000, 1_000_000, 1));
        // warm the bucket
        long t = FlatExecutionEngine.tryAcquire(resourceId);
        FlatExecutionEngine.release(resourceId, t, true);
    }

    @Benchmark
    public long tryAcquire() {
        return FlatExecutionEngine.tryAcquire(resourceId);
    }

    @Benchmark
    public void acquireRelease() {
        long token = FlatExecutionEngine.tryAcquire(resourceId);
        FlatExecutionEngine.release(resourceId, token, true);
    }
}
