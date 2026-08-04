package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import dev.circuitbreaker.core.ResourceState;
import dev.circuitbreaker.core.reload.ConfigSwapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2 (AA N4 residual): a token acquired while the circuit breaker was ON must not re-trip the
 * breaker after it has been hot-swapped OFF.
 *
 * <p>Scenario: register with CB on (minCalls=3) → two failures build count=2, ppm≈high (just
 * under the trip threshold) → hot-swap to a CB-off config (mask without 0x01). The off-config's
 * degraded defaults (minCalls=1, errThresholdPpm=0) would trip on ANY accumulated state, so the
 * stale token's release must neither trip the breaker nor feed its EWMA — the operator explicitly
 * disabled the breaker, and a false OPEN would re-block traffic.
 *
 * <p>The near-threshold EWMA state is driven with fabricated nowMs through
 * {@link EwmaCircuitBreaker#release} (same package) — deterministic, no wall-clock sleep. The
 * stale-token release goes through the real engine ({@link FlatExecutionEngine#release}) so the
 * token-mask/version plumbing and the N4 guard are exercised end to end.</p>
 */
class StaleTokenAfterCbHotSwapOffTest {

    @Test
    void staleReleaseAfterCbHotSwapOffDoesNotTripBreaker() {
        ResourceConfig cfg1 = new ResourceConfig(
                ResourceConfig.MASK_CIRCUIT_BREAKER, // 0x01
                0, 0,          // qps/capacity unused (no rate limit)
                500_000,       // errThresholdPpm 50%
                3,             // minCalls — 2 failures stay just under the trip condition
                1000,          // openMillis
                1000,          // ewmaTauMs
                0,             // concurrencyLimit unused
                1);            // version
        int rid = ResourceManager.register(cfg1);
        ResourceState st = ResourceManager.state(rid);

        // Build near-threshold evidence deterministically: 2 failures at τ intervals → count=2,
        // ppm≈865k, still CLOSED (2 < minCalls 3). Fabricated nowMs; no wall-clock dependency.
        EwmaCircuitBreaker.release(st, 1000L, false, cfg1, true);
        EwmaCircuitBreaker.release(st, 2000L, false, cfg1, true);
        assertEquals(2, EwmaCircuitBreaker.ewCount(st.ewmaState.get()));
        assertTrue(EwmaCircuitBreaker.ewPpm(st.ewmaState.get()) > 500_000,
                "two failures must push ppm past the 50% threshold");
        assertEquals(EwmaCircuitBreaker.CLOSED, EwmaCircuitBreaker.brState(st.breakerState.get()));

        // Acquire a real token under cfg1 (stale after the swap below).
        long stale = FlatExecutionEngine.tryAcquire(rid);
        assertTrue(stale >= 0, "pre-swap acquire must pass");

        // Hot-swap the breaker OFF entirely (mask = 0, version 2).
        ResourceConfig cfg2 = new ResourceConfig(0, 0, 0, 0, 1, 1000, 1000, 0, 2);
        ConfigSwapper.swap(rid, cfg2);

        // Stale token (acquired under cfg1, carries the CB mask) released as a FAILURE after the
        // swap. Old behavior: CLOSED re-check with degraded defaults (count 2 >= minCalls 1,
        // ppm >= 0) → false OPEN. New behavior: release() early-returns when the CURRENT cfg no
        // longer has CB — and must not have fed the EWMA either.
        long ppmBefore = st.ewmaErrorRatePpm();
        FlatExecutionEngine.release(rid, stale, false);

        // The breaker must still be CLOSED → a fresh acquire passes (no CIRCUIT_BREAKER block).
        long t3 = FlatExecutionEngine.tryAcquire(rid);
        assertTrue(t3 >= 0, "breaker must not be tripped by a stale release after CB was disabled; got " + t3);
        assertEquals(ppmBefore, st.ewmaErrorRatePpm(),
                "stale release after CB-off must not pollute the EWMA");
        if (t3 >= 0) {
            FlatExecutionEngine.release(rid, t3, true);
        }
    }
}
