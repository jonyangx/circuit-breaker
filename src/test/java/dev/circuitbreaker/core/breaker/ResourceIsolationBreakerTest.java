package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Breaker-specific resource isolation tests using package-private access.
 * Verifies that tripping resource A's breaker does NOT contaminate resource B's state.
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §10.
 */
class ResourceIsolationBreakerTest {

    private static ResourceConfig cfg() {
        return new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
    }

    /** Trip A with τ-spaced failures. A is OPEN; B is still CLOSED and passes. */
    @Test
    void breakerTripIsPerResource() {
        ResourceConfig c = cfg();
        ResourceState a = new ResourceState();
        ResourceState b = new ResourceState();

        // Trip A with τ-spaced failures: trip happens at i=5 (t=5000), endTime=5000+1000=6000.
        EwmaCircuitBreaker.tryAcquire(a, c, 0);
        for (int i = 1; i <= 7; i++) {
            EwmaCircuitBreaker.release(a, i * 1000L, false, c, true);
        }
        assertThat(EwmaCircuitBreaker.brState(a.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
        // At t=5500, before endTime(6000) — A is still OPEN and blocks traffic.
        assertThat(EwmaCircuitBreaker.tryAcquire(a, c, 5500)).isFalse();

        // B is unaffected
        assertThat(EwmaCircuitBreaker.brState(b.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
        assertThat(EwmaCircuitBreaker.tryAcquire(b, c, 0)).isTrue();

        // Trip B independently
        EwmaCircuitBreaker.tryAcquire(b, c, 0);
        for (int i = 1; i <= 7; i++) {
            EwmaCircuitBreaker.release(b, i * 1000L, false, c, true);
        }
        assertThat(EwmaCircuitBreaker.brState(b.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);

        // A's state unchanged by B's activity
        assertThat(EwmaCircuitBreaker.brState(a.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    /** A's EWMA error rate never contaminates B's EWMA. */
    @Test
    void ewmaErrorRateIsPerResource() {
        ResourceConfig c = cfg();
        ResourceState a = new ResourceState();
        ResourceState b = new ResourceState();

        EwmaCircuitBreaker.tryAcquire(a, c, 0);
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.release(a, i * 1000L, false, c, true);
        }
        int aPpm = EwmaCircuitBreaker.ewPpm(a.ewmaState.get());
        assertThat(aPpm).isGreaterThan(800_000); // A's error rate is high

        // B's ppm is still 0
        assertThat(EwmaCircuitBreaker.ewPpm(b.ewmaState.get())).isZero();

        // A success on B leaves A's ppm untouched
        EwmaCircuitBreaker.tryAcquire(b, c, 0);
        EwmaCircuitBreaker.release(b, 1000L, true, c, true);
        assertThat(EwmaCircuitBreaker.ewPpm(b.ewmaState.get())).isZero(); // B success → 0
        assertThat(EwmaCircuitBreaker.ewPpm(a.ewmaState.get())).isEqualTo(aPpm); // A unchanged
    }

    /** A's breaker generation change doesn't affect B's generation. */
    @Test
    void generationIsPerResource() {
        ResourceConfig c = cfg();
        ResourceState a = new ResourceState();
        ResourceState b = new ResourceState();

        // Initial gen is 0 for both
        assertThat(EwmaCircuitBreaker.brGen(a.breakerState.get())).isZero();
        assertThat(EwmaCircuitBreaker.brGen(b.breakerState.get())).isZero();

        // Trip A: gen bumps multiple times (CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED)
        EwmaCircuitBreaker.tryAcquire(a, c, 0);
        for (int i = 1; i <= 7; i++) {
            EwmaCircuitBreaker.release(a, i * 1000L, false, c, true);
        }
        assertThat(EwmaCircuitBreaker.brGen(a.breakerState.get())).isGreaterThan(0);

        // B's gen is still 0
        assertThat(EwmaCircuitBreaker.brGen(b.breakerState.get())).isZero();
    }
}
