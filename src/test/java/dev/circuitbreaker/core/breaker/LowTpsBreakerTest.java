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
