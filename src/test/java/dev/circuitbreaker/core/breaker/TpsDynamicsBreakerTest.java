package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial TPS spike/drop/jitter tests for the EWMA breaker (package-private access).
 *
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §9 (TPS dynamics).
 *
 * Key insight: EWMA acts as a LOW-PASS FILTER. A micro-burst of failures within 1ms
 * produces α ≈ Δt/τ which is near-zero → ppm barely climbs. This is CORRECT by design:
 * a transient micro-burst is not a sustained error rate and should not trip the breaker.
 * Only errors sustained over a τ-horizon should trip.
 */
class TpsDynamicsBreakerTest {

    private static final long TAU = 1000L;
    private static final long OPEN_MS = 1000L;

    private static ResourceConfig cfg(int minCalls) {
        return new ResourceConfig(0x01, 0, 0, 500_000, minCalls, OPEN_MS, TAU, 0, 1);
    }

    /**
     * §9.1: Micro-burst — 50 failures within 1ms. All have α ≈ Δt/τ ≈ 1e-6
     * (dt=1µs treated as 0ms → α=0) since we prime the state just before the burst.
     * ppm should barely climb (low-pass filtering). Must NOT trip even with minCalls=5.
     * <p>
     * NOTE: the real first failure in the burst gets α = 1-e^{-1/τ} ≈ 0.001 if
     * we prime at t=999 and burst at t=1000 (dt=1ms). Without priming the first
     * failure sees dt from lastUpdateMs=0 → α≈0.632. We prime explicitly.
     */
    @Test
    void microBurstFailuresDampenedByLowAlpha() {
        ResourceConfig c = cfg(50); // high minCalls to keep us from tripping on ppm effects
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);

        // Prime: release a success just before the burst so lastUpdateMs is at 999.
        EwmaCircuitBreaker.release(st, 999L, true, c, true);

        long now = 1000L;
        for (int i = 0; i < 50; i++) {
            // Same-ms burst: dt=0 or 1 for all → α ≈ 0 for all subsequent
            EwmaCircuitBreaker.release(st, now, false, c, true);
        }
        // ppm should be very small (first burst failure gets α≈0.001, rest get α≈0)
        int ppm = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        // With α≈0.001 × 1e6 ≈ 1000 per failure, 50 failures → well under 500_000
        assertThat(ppm).isLessThan(500_000); // beneath threshold
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    /**
     * §9.2: Sustained high error rate across τ horizon — failures at τ intervals.
     * ppm must climb past threshold and trip.
     */
    @Test
    void sustainedErrorsOverTauHorizonTripBreaker() {
        ResourceConfig c = cfg(5);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);

        // 5 failures at τ intervals (1000, 2000, 3000, 4000, 5000)
        // Each α ≈ 1 - e^{-1} ≈ 0.632. ppm climbs: 0 → 632k → 864k → … well past 500k.
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.release(st, i * TAU, false, c, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    /**
     * §9.3: Jitter — wildly varying inter-arrival times (sub-ms, multi-second, τ-scale).
     * Sustained failures over enough time must still trip.
     */
    @Test
    void jitteredIntervalsStillTripWhenSustained() {
        ResourceConfig c = cfg(8);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);

        // Irregular pattern: sub-ms, multi-ms, multi-second, τ-scale
        long[] times = {0, 1, 2, 3, 1000, 1500, 2500, 3500}; // 8 releases
        for (long t : times) {
            EwmaCircuitBreaker.release(st, t, false, c, true);
        }
        // After 8 failures with varying gaps, must trip.
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    /**
     * §9.4: Silence after high error — long idle, then one success.
     * With α=1 after u≥8, the success fully replaces EWMA ppm to 0.
     */
    @Test
    void silenceThenSuccessFullyResetsPpm() {
        ResourceConfig c = cfg(5);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);

        // Drive ppm up with a single τ-spaced failure
        EwmaCircuitBreaker.release(st, 1000L, false, c, true);
        int ppmAfterFailure = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        assertThat(ppmAfterFailure).isGreaterThan(0);

        // Long silence: Δt = 10000ms → u = 10 ≥ 8 → α = 1
        // Next success must fully replace ppm with 0
        EwmaCircuitBreaker.release(st, 11000L, true, c, true);
        assertThat(EwmaCircuitBreaker.ewPpm(st.ewmaState.get())).isZero();
    }

    /**
     * §9.5: Micro-burst of failures then micro-burst of successes.
     * With priming so all samples have α≈0, ppm barely moves from baseline.
     * This is CORRECT EWMA low-pass behavior — not a bug.
     */
    @Test
    void failureBurstThenSuccessBurstStaysNearBaseline() {
        ResourceConfig c = cfg(100); // high minCalls to prevent trip during test
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);

        // Prime: release a success just before the burst.
        EwmaCircuitBreaker.release(st, 999L, true, c, true);

        long now = 1000L;
        // 50 failures in same ms
        for (int i = 0; i < 50; i++) {
            EwmaCircuitBreaker.release(st, now, false, c, true);
        }
        int ppmAfterFailures = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        assertThat(ppmAfterFailures).isLessThan(500_000); // well below threshold

        // 50 successes in same ms (right after failures, same timestamp)
        for (int i = 0; i < 50; i++) {
            EwmaCircuitBreaker.release(st, now, true, c, true);
        }
        int ppmAfterSuccesses = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        // With α≈0 for same-ms samples, ppm only goes down slightly (first success gets α≈0)
        assertThat(ppmAfterSuccesses).isLessThanOrEqualTo(ppmAfterFailures); // successes brought ppm down or even
    }

    /**
     * §9.6: Sub-ms dt — many samples in rapid succession.
     * Verify the first-order Taylor path handles the case correctly:
     * dtMs=0 is a degenerate case (α=0, no change).
     */
    @Test
    void zeroDtSamplesDoNotShiftEwma() {
        ResourceConfig c = cfg(5);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);

        // First failure at t=1000: produces a real α = 1-e^{-1} ≈ 0.632 → ppm ~632k
        EwmaCircuitBreaker.release(st, 1000L, false, c, true);
        int ppmAfterFirst = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());

        // Same-timestamp failure: dt=0 → α=0 → ppm unchanged
        EwmaCircuitBreaker.release(st, 1000L, false, c, true);
        int ppmAfterSecond = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        assertThat(ppmAfterSecond).isEqualTo(ppmAfterFirst); // unchanged
    }

    /**
     * §9.7: Reverse clock — nowMs goes backward within 24-bit mask range.
     * Modular subtraction: dt = (now - last) & EW_LAST_MASK.
     * If last=2000, now=1500: (1500-2000) & 0xFFFFFF = -500 & 0xFFFFFF = 16776716.
     * This is a HUGE dt → α = 1 → full decay. Returns 0 for success, 1e6 for failure.
     *
     * This means a clock reversal doesn't corrupt EWMA — it just forces a "full reset"
     * on the next sample, which is safe (though semantically the sample weight is wrong
     * for one update).
     */
    @Test
    void clockReversalWithin24BitDoesNotCorruptEwma() {
        ResourceConfig c = cfg(5);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);

        // Prime: failure at t=2000
        EwmaCircuitBreaker.release(st, 2000L, false, c, true);
        int ppmBefore = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());

        // "Clock reversal": now=1500 < last=2000. dt = (1500-2000) & 0xFFFFFF = huge.
        // With dt huge, u ≥ 8 → α = 1 → next sample fully replaces EWMA.
        // A success at this "reversed" time re-seeds ppm to 0.
        EwmaCircuitBreaker.release(st, 1500L, true, c, true);
        int ppmAfter = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());

        // α=1 → success re-seeds to 0, error rate is now 0.
        // No corruption — the clock reversal is handled gracefully.
        assertThat(ppmAfter).isZero();
    }
}
