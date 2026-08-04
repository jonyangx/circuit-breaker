package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Low-TPS tests for the EWMA breaker (package-private access). Aligned with
 * docs/system/07_ALGORITHM_DEEP_DIVE.md §6.2/§6.3.
 */
class LowTpsBreakerTest {

    // ---- §6.2: α saturation and the Taylor hot path ----

    @Test
    void alphaSaturatesToOneForLongIdle() {
        assertThat(EwmaAlpha.alpha(20000L, 1000.0)).isEqualTo(1.0f);
        assertThat(EwmaAlpha.alpha(40000L, 5000.0)).isEqualTo(1.0f);
    }

    @Test
    void alphaUsesFirstOrderForSmallDt() {
        // u = 1/5000 = 0.0002 ≤ 1/128 → α ≈ u
        float a = EwmaAlpha.alpha(1L, 5000.0);
        assertThat(a).isCloseTo(0.0002f, Offset.offset(1e-5f));
    }

    // ---- §6.2: EWMA at sparse τ-spaced failures still trips ----

    @Test
    void sparseTauSpacedFailuresStillTrip() {
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.release(st, i * 1000L, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 5500)).isFalse();
    }

    @Test
    void sparseFourFailuresNeverTrips() {
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        for (int i = 1; i <= 4; i++) {
            EwmaCircuitBreaker.release(st, i * 1000L, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    // ---- §6.2: After long idle (α=1), EWMA = current sample ----

    @Test
    void sparseLongIdleResetsEwma() {
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        // Failure at t=1000 raises ppm
        EwmaCircuitBreaker.release(st, 1000L, false, cfg, true);
        int ppmAfterFirst = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        assertThat(ppmAfterFirst).isGreaterThan(500_000);

        // Long idle: Δt=10000ms → u=10 ≥ 8 → α=1. Next success (0) fully replaces EWMA.
        EwmaCircuitBreaker.release(st, 11000L, true, cfg, true);
        assertThat(EwmaCircuitBreaker.ewPpm(st.ewmaState.get())).isZero();
    }

    // ---- §6.3: sparse calls — count accumulates faithfully below minCalls ----

    @Test
    void sparseCountAccumulatesUnderMinCalls() {
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 20, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        for (int i = 1; i <= 10; i++) {
            EwmaCircuitBreaker.release(st, i * 1000L, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    // ---- §6.2: long idle must fully decay the EWMA — one stale failure must not trip ----

    /**
     * Defect 2 regression: after an idle long enough to fully decay the EWMA (Δt > 8τ), a single
     * failure must NOT trip the breaker. Before the fix, the sample count was preserved across the
     * idle (count=4 → 5 on the next sample) while α=1 replaced the ppm — so one stale failure
     * after the idle tripped a breaker that had seen no recent failures. The fix re-seeds (count=1)
     * so minCalls must be re-earned with fresh samples.
     */
    @Test
    void oneStaleFailureAfterLongIdleDoesNotTrip() {
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        // 4 τ-spaced failures: ppm high, count=4 < minCalls=5 → breaker stays CLOSED.
        for (int i = 1; i <= 4; i++) {
            EwmaCircuitBreaker.release(st, i * 1000L, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);

        // Long idle Δt=10_000ms (10τ > 8τ): EWMA fully decays.
        // A single failure at t=14000 must not trip: on the fixed build it re-seeds (count=1);
        // on the buggy build count survives (4→5) and ppm=1.0M ≥ threshold → false OPEN.
        EwmaCircuitBreaker.release(st, 14_000L, false, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get()))
                .as("one stale failure after 10τ idle must not trip the breaker")
                .isEqualTo(EwmaCircuitBreaker.CLOSED);
        assertThat(EwmaCircuitBreaker.ewCount(st.ewmaState.get()))
                .as("long idle must re-seed the sample count (minCalls re-earned from scratch)")
                .isEqualTo(1);
    }

    // ---- α at degenerate input (dtMs ≤ 0, tauMs ≤ 0) ----

    @Test
    void alphaZeroForSameInstant() {
        assertThat(EwmaAlpha.alpha(0L, 1000.0)).isEqualTo(0.0f);
        assertThat(EwmaAlpha.alpha(-1L, 1000.0)).isEqualTo(0.0f);
    }

    @Test
    void alphaFullForDegenerateTau() {
        assertThat(EwmaAlpha.alpha(1L, 0.0)).isEqualTo(1.0f);
        assertThat(EwmaAlpha.alpha(1L, -1.0)).isEqualTo(1.0f);
    }
}
