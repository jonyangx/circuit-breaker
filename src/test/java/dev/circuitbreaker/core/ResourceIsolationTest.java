package dev.circuitbreaker.core;

import dev.circuitbreaker.core.system.SystemOverload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-resource isolation + avalanche/cascade prevention tests.
 *
 * Verifies the core architectural claim: each resource (service endpoint) has INDEPENDENT
 * governance state — one resource's failure MUST NOT trip, rate-limit, or block another
 * resource's traffic. The ONLY intentional cross-resource coupling is SystemOverload
 * (system-level CPU shedding), which is global by design.
 *
 * All assertions use only the PUBLIC API (FlatExecutionEngine, ResourceManager, ResourceState).
 * For package-private breaker state introspection, see breaker/ResourceIsolationBreakerTest.
 *
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §10 (multi-service isolation).
 */
class ResourceIsolationTest {

    @AfterEach
    void resetOverload() {
        SystemOverload.setShedPermilleForTest(0);
    }

    // ---- Token bucket isolation: draining A never starves B ----

    @Test
    void tokenBucketDrainIsPerResource() {
        int ridA = ResourceManager.register(
                new ResourceConfig(0x02, 10, 10, 0, 1, 1000, 1000, 0, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x02, 10, 10, 0, 1, 1000, 1000, 0, 1));

        // Drain A's bucket completely
        for (int i = 0; i < 10; i++) {
            long t = FlatExecutionEngine.tryAcquire(ridA);
            if (t >= 0) FlatExecutionEngine.release(ridA, t, true);
        }
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isEqualTo(BlockCode.RATE_LIMITER); // A drained

        // B still has its full burst — A's drain did not touch B
        long b1 = FlatExecutionEngine.tryAcquire(ridB);
        assertThat(b1).isGreaterThanOrEqualTo(0);
        FlatExecutionEngine.release(ridB, b1, true);
    }

    // ---- Concurrency isolation: saturating A never blocks B ----

    @Test
    void concurrencySaturationIsPerResource() {
        int ridA = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 3, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 3, 1));

        // Saturate A (hold 3 slots). With limit < SEG, per-segment caps may transiently block an
        // acquire before the global limit is reached, so retry until 3 slots are held.
        long[] a = acquireN(ridA, 3);
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isEqualTo(BlockCode.CONCURRENCY); // A saturated

        // B's concurrency is completely unaffected — 3 slots free (retry for the same reason)
        long[] b = acquireN(ridB, 3);
        assertThat(FlatExecutionEngine.tryAcquire(ridB)).isEqualTo(BlockCode.CONCURRENCY); // B independently saturated

        // Release A's slots — B's state must be unaffected (B still holds its own 3 slots)
        for (long t : a) FlatExecutionEngine.release(ridA, t, true);
        assertThat(FlatExecutionEngine.tryAcquire(ridB)).isEqualTo(BlockCode.CONCURRENCY); // B still saturated

        // Cleanup B's slots
        for (long t : b) FlatExecutionEngine.release(ridB, t, true);
    }

    // ---- Stat counter isolation: passCount/blockCount are per-resource ----

    @Test
    void statCountersArePerResource() {
        int ridA = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1, 1));

        long a1 = FlatExecutionEngine.tryAcquire(ridA); // pass A
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isEqualTo(BlockCode.CONCURRENCY); // block A
        FlatExecutionEngine.release(ridA, a1, true);

        assertThat(ResourceManager.state(ridA).passCount()).isEqualTo(1);
        assertThat(ResourceManager.state(ridA).blockCount()).isEqualTo(1);
        assertThat(ResourceManager.state(ridB).passCount()).isZero();
        assertThat(ResourceManager.state(ridB).blockCount()).isZero();
    }

    // ---- Avalanche scenario: A in full failure cascade, B continues healthy ----
    // NOTE: A tight loop of failures within 1ms → dt≈0 → α≈0 → EWMA barely climbs (low-pass
    // filtering by design, see §8.1). To reliably drive a trip via the real-clock engine,
    // we use a tiny τ=1ms so α≈1.0 even at 10ms gaps, and minCalls=2 so only ~3 spaced
    // failures are needed (~30ms total) instead of seconds. The KEY isolation test is that
    // A's tripped state never leaks into B.

    @Test
    void avalancheScenarioAResourceFailsBStaysHealthy() throws InterruptedException {
        int ridA = ResourceManager.register(
                new ResourceConfig(0x01, 0, 0, 500_000, 2, 1000, 1, 0, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x07, 1_000_000, 1_000_000, 500_000, 5, 1000, 1000, 100, 1));

        // Drive A into failure with τ=1ms (α≈1 at 10ms gaps): 3 spaced failures → count=3≥2,
        // ppm≈1e6 → trip. Total time ~30ms instead of seconds.
        for (int i = 0; i < 5; i++) {
            long t = FlatExecutionEngine.tryAcquire(ridA);
            if (t >= 0) FlatExecutionEngine.release(ridA, t, false);
            Thread.sleep(10);
        }
        // A is now OPEN (tripped) — its callers get CIRCUIT_BREAKER
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isEqualTo(BlockCode.CIRCUIT_BREAKER);

        // B continues serving traffic normally — NO cascade, NO avalanche
        long b1 = FlatExecutionEngine.tryAcquire(ridB);
        assertThat(b1).isGreaterThanOrEqualTo(0);
        FlatExecutionEngine.release(ridB, b1, true);
        long b2 = FlatExecutionEngine.tryAcquire(ridB);
        assertThat(b2).isGreaterThanOrEqualTo(0);
        FlatExecutionEngine.release(ridB, b2, true);

        // B's stats show only B's own traffic
        ResourceState bState = ResourceManager.state(ridB);
        assertThat(bState.passCount()).isGreaterThan(0);
        assertThat(bState.blockCount()).as("B was never blocked by A's failure cascade").isZero();
    }

    // ---- SystemOverload is the ONLY intentional cross-resource coupling ----

    @Test
    void systemOverloadIsTheOnlyCrossResourceCoupling() {
        int ridA = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));

        // Normal: both pass
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isGreaterThanOrEqualTo(0);
        assertThat(FlatExecutionEngine.tryAcquire(ridB)).isGreaterThanOrEqualTo(0);

        // Inject system overload → BOTH resources shed (global, by design)
        SystemOverload.setShedPermilleForTest(1000);
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isEqualTo(BlockCode.SYSTEM_OVERLOAD);
        assertThat(FlatExecutionEngine.tryAcquire(ridB)).isEqualTo(BlockCode.SYSTEM_OVERLOAD);

        // Clear overload → BOTH recover simultaneously
        SystemOverload.setShedPermilleForTest(0);
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isGreaterThanOrEqualTo(0);
        assertThat(FlatExecutionEngine.tryAcquire(ridB)).isGreaterThanOrEqualTo(0);
    }

    // ---- Resource IDs are unique and have independent state ----

    @Test
    void resourceRegistrationBoundedAndUnique() {
        int rid1 = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1, 1));
        int rid2 = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1, 1));
        assertThat(rid1).isNotEqualTo(rid2);
        assertThat(ResourceManager.state(rid1)).isNotSameAs(ResourceManager.state(rid2));
    }

    // ---- Breaker isolation at engine level: tripping A never affects B ----

    @Test
    void breakerTripAtEngineLevelIsPerResource() throws InterruptedException {
        int ridA = ResourceManager.register(
                new ResourceConfig(0x01, 0, 0, 500_000, 2, 1000, 1, 0, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x01, 0, 0, 500_000, 2, 1000, 1, 0, 1));

        // Initially both pass
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isGreaterThanOrEqualTo(0);
        assertThat(FlatExecutionEngine.tryAcquire(ridB)).isGreaterThanOrEqualTo(0);

        // Drive A to trip with timed failures (τ=1ms → α≈1 at 10ms gaps, so ~3 failures do it)
        for (int i = 0; i < 5; i++) {
            long t = FlatExecutionEngine.tryAcquire(ridA);
            if (t >= 0) FlatExecutionEngine.release(ridA, t, false);
            Thread.sleep(10);
        }

        // A is blocked (tripped)
        assertThat(FlatExecutionEngine.tryAcquire(ridA)).isEqualTo(BlockCode.CIRCUIT_BREAKER);

        // B still passes (was never affected by A)
        assertThat(FlatExecutionEngine.tryAcquire(ridB)).isGreaterThanOrEqualTo(0);

        // B's EWMA error rate is 0 (no failures influenced it)
        assertThat(ResourceManager.state(ridB).ewmaErrorRatePpm()).isZero();
    }

    /**
     * Acquire exactly {@code n} tokens from {@code rid}, retrying past transient per-segment caps.
     *
     * <p>When {@code concurrencyLimit < SEG}, the per-segment cap (ceil(limit/SEG)=1) can block an
     * acquire even when the global limit hasn't been reached (two ThreadLocalRandom probes hit the
     * same segment). This helper retries until {@code n} tokens are held, so isolation tests stay
     * deterministic without loosening their assertions.</p>
     */
    private static long[] acquireN(int rid, int n) {
        long[] tokens = new long[n];
        int got = 0;
        int attempts = 0;
        while (got < n) {
            long t = FlatExecutionEngine.tryAcquire(rid);
            if (t >= 0) {
                tokens[got++] = t;
            }
            assertThat(++attempts).isLessThan(n * 100); // safety bound against infinite loop
        }
        return tokens;
    }
}
