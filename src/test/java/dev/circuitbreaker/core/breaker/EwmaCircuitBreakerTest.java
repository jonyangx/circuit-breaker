package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EwmaCircuitBreaker 3-state machine + generation tests (UC-005; BR-020/024/025). TC-CAP-CB-001..005.
 * Failures are spaced ~τ apart so the time-decay EWMA climbs to the threshold within minCalls samples.
 */
class EwmaCircuitBreakerTest {

    private static final long TAU = 1000L;
    private static final long OPEN = 1000L;

    private static ResourceConfig breakerCfg() {
        // mask 0x01, 50% threshold, minCalls 5, open 1000ms, τ 1000ms
        return new ResourceConfig(0x01, 0, 0, 500_000, 5, OPEN, TAU, 0, 1);
    }

    /** Drive a fresh state to OPEN via 5 failures spaced τ apart. endTime ≈ 5τ + open = 6000. */
    private static ResourceState trip() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();
        EwmaCircuitBreaker.tryAcquire(st, 0);
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true); // now = 1000..5000
        }
        return st;
    }

    @Test
    void tripsOnSustainedHighErrorRate() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
        assertThat(EwmaCircuitBreaker.tryAcquire(st, 5500)).isFalse(); // before endTime(~6000) → blocked
    }

    @Test
    void halfOpenSingleProbeThenRecover() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        assertThat(EwmaCircuitBreaker.tryAcquire(st, 7000)).isTrue();  // past endTime → probe wins OPEN→HALF_OPEN
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);
        assertThat(EwmaCircuitBreaker.tryAcquire(st, 7000)).isFalse(); // others blocked while probe in flight
        EwmaCircuitBreaker.release(st, 7000, true, cfg, true);          // probe success → CLOSED
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    @Test
    void generationPreventsImmediateReTripAfterRecovery() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        EwmaCircuitBreaker.tryAcquire(st, 7000);               // → HALF_OPEN
        EwmaCircuitBreaker.release(st, 7000, true, cfg, true);  // → CLOSED (gen bumped → stale EWMA invalidated)
        // one failure right after recovery: count re-seeded to 1 < minCalls(5) → must NOT re-trip (BR-024)
        EwmaCircuitBreaker.release(st, 7001, false, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    @Test
    void probeFailureReopensBreaker() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        EwmaCircuitBreaker.tryAcquire(st, 7000);                // → HALF_OPEN
        EwmaCircuitBreaker.release(st, 7000, false, cfg, true); // probe fail → OPEN
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }
}
