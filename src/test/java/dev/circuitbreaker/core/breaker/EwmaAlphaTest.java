package dev.circuitbreaker.core.breaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** EwmaAlpha piecewise approximation tests (UC-005; BR-021). TC-CAP-CB-006. */
class EwmaAlphaTest {
    private static final double TAU = 5000.0;

    @Test
    void noDecayAtZeroDt() {
        assertThat(EwmaAlpha.alpha(0, TAU)).isEqualTo(0.0f);
    }

    @Test
    void smallUFirstOrder() {
        // u = dt/τ = 1/5000 = 0.0002 <= 1/128 → α ≈ u
        float a = EwmaAlpha.alpha(1L, TAU);
        assertThat(a).isCloseTo(0.0002f, within(1e-5f));
    }

    @Test
    void midUInterpolationAccurate() {
        // u = 1 → α = 1 - e^-1 ≈ 0.6321
        float a = EwmaAlpha.alpha(5000L, TAU);
        assertThat(a).isCloseTo(0.6321f, within(1e-3f));
    }

    @Test
    void largeUSaturated() {
        // u = 20 >= 8 → α = 1
        assertThat(EwmaAlpha.alpha(100_000L, TAU)).isEqualTo(1.0f);
    }
}
