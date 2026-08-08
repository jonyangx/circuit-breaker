package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LT-5 boundary lock for the EWMA long-idle re-seed (docs/qa/EXTREME_TPS_SA_REQUIREMENTS.md §2 LT-5;
 * docs/qa/AA_EXTREME_TPS_REVIEW_R3.md §6 F2).
 *
 * <p>Semantics under test: {@code EwmaCircuitBreaker.updateEwma} re-seeds (count = 1) whenever the gap
 * since the last committed update is {@code >= max(8τ, EW_IDLE_RE_SEED_FLOOR_MS = 100)}. The 100ms floor
 * exists because the relative threshold alone degenerates at tiny τ: with τ=1ms any ordinary 8ms+ spacing
 * would reset count, so a sustained failure burst could never accumulate minCalls. Below the floor a gap is
 * "ordinary traffic" and evidence accumulates normally. This is a {@code declared design trade-off}
 * (source comment {@code EwmaCircuitBreaker.java:172-177}), NOT a defect.</p>
 *
 * <p>Consequence locked here: with τ=1ms, minCalls=5:
 * <ul>
 *   <li>gap {@code >= 100ms} (sparse 100% failures) → every sample re-seeds count to 1 → count never reaches
 *       minCalls → breaker stays CLOSED forever (the R1 floor's accepted semantics);</li>
 *   <li>gap {@code < 100ms} (99ms just below the floor, or dense 10ms) → samples are ordinary traffic →
 *       count accumulates → 5th consecutive failure crosses minCalls and trips OPEN.</li>
 * </ul></p>
 *
 * <p>These tests pin the exact 100ms threshold on both sides so a future change to
 * {@code EW_IDLE_RE_SEED_FLOOR_MS} or the re-seed guard cannot silently flip the boundary. All times are
 * injected directly into {@code release(...)}; no wall-clock dependence (AC-3).</p>
 */
class EwmaReseedBoundaryTest {

    /**
     * τ=1ms (tiny half-life → relative re-seed threshold would be 8ms), minCalls=5, errThresholdPpm=50%.
     * gap = 100ms is exactly {@code EW_IDLE_RE_SEED_FLOOR_MS} → re-seed every sample.
     */
    private static ResourceConfig tinyTauCfg() {
        return new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1, 0, 1);
    }

    /** Prime the breaker state (matches the established test convention). */
    private static void prime(ResourceState st, ResourceConfig cfg) {
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
    }

    /**
     * Re-seed side of the LT-5 boundary: gap == 100ms (>= floor). Every 100% failure re-seeds count to 1,
     * so after ANY number of samples count stays 1, minCalls (5) is never reached and the breaker never
     * trips — even though the traffic is 100% failing. This is the R1 100ms-floor design trade-off, locked
     * so a future guard change cannot silently flip it into a spurious trip (or into silent non-trip).
     */
    @Test
    void sparseFailuresAtReseedFloorNeverAccumulateCountNorTrip() {
        ResourceConfig cfg = tinyTauCfg();
        ResourceState st = new ResourceState();
        prime(st, cfg);

        for (int i = 1; i <= 20; i++) {
            EwmaCircuitBreaker.release(st, i * 100L, false, cfg, true); // t = 100..2000, gap = 100ms
            assertThat(EwmaCircuitBreaker.ewCount(st.ewmaState.get()))
                    .as("gap=100ms (>= floor) must re-seed count to 1 on every sample, sample #%d", i)
                    .isEqualTo(1);
            assertThat(EwmaCircuitBreaker.brState(st.breakerState.get()))
                    .as("count never reaches minCalls=5 → breaker must stay CLOSED, sample #%d", i)
                    .isEqualTo(EwmaCircuitBreaker.CLOSED);
        }
        // Still CLOSED → traffic admitted even after an arbitrarily long sparse 100%-failure run.
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 2100)).isTrue();
    }

    /**
     * Boundary contrast, just below the floor: gap == 99ms (< 100ms). The 16ms-quantized modular dt is 96ms
     * (< 100ms floor) and the absolute guard also sees 99ms (< 100ms), so samples are ordinary traffic:
     * count accumulates 1..5 and the 5th consecutive failure crosses minCalls → breaker trips OPEN.
     * This is the exact threshold boundary (100ms - 1ms).
     */
    @Test
    void failuresJustBelowReseedFloorAccumulateCountAndTrip() {
        ResourceConfig cfg = tinyTauCfg();
        ResourceState st = new ResourceState();
        prime(st, cfg);

        for (int i = 1; i <= 4; i++) {
            EwmaCircuitBreaker.release(st, i * 99L, false, cfg, true); // t = 99..396, gap = 99ms
            assertThat(EwmaCircuitBreaker.ewCount(st.ewmaState.get()))
                    .as("gap=99ms (< floor) must accumulate count, sample #%d", i)
                    .isEqualTo(i);
            assertThat(EwmaCircuitBreaker.brState(st.breakerState.get()))
                    .as("below minCalls=5 → still CLOSED, sample #%d", i)
                    .isEqualTo(EwmaCircuitBreaker.CLOSED);
        }
        // 5th failure (t=495) crosses minCalls=5 → trip OPEN.
        EwmaCircuitBreaker.release(st, 495L, false, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get()))
                .as("5th consecutive 99ms-spaced failure must trip")
                .isEqualTo(EwmaCircuitBreaker.OPEN);
        // OPEN until endTime (495 + openMillis 1000 = 1495) → traffic blocked.
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 500)).isFalse();
    }

    /**
     * Ordinary-traffic contrast: gap == 10ms, far below the floor. count accumulates to minCalls and the
     * breaker trips — the intuitive dense-failure behavior that the 100ms floor must NOT have broken.
     */
    @Test
    void denseFailuresWellBelowReseedFloorAccumulateCountAndTrip() {
        ResourceConfig cfg = tinyTauCfg();
        ResourceState st = new ResourceState();
        prime(st, cfg);

        for (int i = 1; i <= 4; i++) {
            EwmaCircuitBreaker.release(st, i * 10L, false, cfg, true); // t = 10..40, gap = 10ms
            assertThat(EwmaCircuitBreaker.ewCount(st.ewmaState.get()))
                    .as("gap=10ms must accumulate count, sample #%d", i)
                    .isEqualTo(i);
            assertThat(EwmaCircuitBreaker.brState(st.breakerState.get()))
                    .as("below minCalls=5 → still CLOSED, sample #%d", i)
                    .isEqualTo(EwmaCircuitBreaker.CLOSED);
        }
        // 5th failure (t=50) crosses minCalls=5 → trip OPEN.
        EwmaCircuitBreaker.release(st, 50L, false, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get()))
                .as("5th consecutive 10ms-spaced failure must trip")
                .isEqualTo(EwmaCircuitBreaker.OPEN);
        // OPEN until endTime (50 + 1000 = 1050) → traffic blocked.
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 60)).isFalse();
    }
}
