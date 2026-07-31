package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup-immunity tests for the EWMA breaker state (uses package-private members).
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §5.2/§5.6.
 */
class StartupImmunityBreakerTest {

    private static ResourceConfig cfg() {
        // mask 0x01, 50% threshold, minCalls 5, open 1000ms, τ 1000ms
        return new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
    }

    /** §5.2: fresh state — a handful of failures (below minCalls) must NEVER trip. */
    @Test
    void startupFewFailuresNeverTrip() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);                    // CLOSED
        for (int i = 1; i <= 4; i++) {                              // only 4 failures < minCalls(5)
            EwmaCircuitBreaker.release(st, i * 1000L, false, c, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
        assertThat(EwmaCircuitBreaker.tryAcquire(st, c, 4500)).isTrue();
    }

    /** §5.2: a single 100%-failure right at startup raises ppm but does NOT trip (count=1 < minCalls). */
    @Test
    void startupSingleFailureRaisesPpmButNeverTrips() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);
        EwmaCircuitBreaker.release(st, 1000L, false, c, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
        assertThat(EwmaCircuitBreaker.ewPpm(st.ewmaState.get())).isGreaterThan(0); // ppm climbed, but no trip
    }

    /** §5.6: a "zombie" ppm from a stale generation is re-seeded on the first write after a transition. */
    @Test
    void startupZombiePpmReseededAfterGenBump() {
        ResourceState st = new ResourceState();
        ResourceConfig c = cfg();
        // Plant a zombie: ewmaState with gen=0 but ppm=1e6 (as if a prior era left high error rate).
        st.breakerState.set(0L); // CLOSED, gen=0
        st.ewmaState.set((0L << 60) | (0L << 36) | (0L << 20) | 0xFFFFFL); // gen=0, count=0, ppm=1e6 (zombie)

        // transition CLOSED → OPEN bumps generation to 1.
        EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.CLOSED, EwmaCircuitBreaker.OPEN, 1000L);
        // OPEN → HALF_OPEN bumps generation again (gen=2).
        EwmaCircuitBreaker.tryAcquire(st, c, 2000L);
        // HALF_OPEN → CLOSED bumps generation again (gen=3).
        EwmaCircuitBreaker.release(st, 2000L, true, c, true);

        // Now breakerState.gen=3, but ewmaState still has gen=0 + zombie ppm.
        // A release in CLOSED must detect the generation mismatch and re-seed with the success sample.
        EwmaCircuitBreaker.release(st, 3000L, true, c, true);
        assertThat(EwmaCircuitBreaker.ewPpm(st.ewmaState.get())).isZero(); // re-seeded with success → 0
    }

    /**
     * §5.1: EWMA evolution depends only on the sequence of Δt (gaps between samples), not on the
     * absolute clock value. We prime each state at its own origin (equalizing the lastUpdateMs
     * reference), then feed identical gap sequences at different absolute times — the resulting
     * ppm must be identical.
     */
    @Test
    void ewmaEvolutionDependsOnGapsNotAbsoluteTime() {
        ResourceConfig c = cfg();
        ResourceState st1 = new ResourceState();
        ResourceState st2 = new ResourceState();
        long offset = 500_000L;
        // Prime each at its own origin so the first real sample's dt is the intended gap.
        EwmaCircuitBreaker.release(st1, 0L, true, c, true);
        EwmaCircuitBreaker.release(st2, offset, true, c, true);
        // st1: τ-spaced failures at absolute [1000, 2000, 3000] (gaps 1000 each).
        long[] t1 = {1000, 2000, 3000};
        for (long t : t1) {
            EwmaCircuitBreaker.release(st1, t, false, c, true);
        }
        // st2: SAME gaps but at a large absolute offset (simulating a long-running process).
        for (long t : t1) {
            EwmaCircuitBreaker.release(st2, t + offset, false, c, true);
        }
        // ppm must be equal — only the gaps mattered.
        assertThat(EwmaCircuitBreaker.ewPpm(st1.ewmaState.get()))
                .isEqualTo(EwmaCircuitBreaker.ewPpm(st2.ewmaState.get()));
    }
}
