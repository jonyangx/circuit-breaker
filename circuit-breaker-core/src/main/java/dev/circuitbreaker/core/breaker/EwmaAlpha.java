package dev.circuitbreaker.core.breaker;

/**
 * Piecewise approximation of α = 1 - exp(-Δt/τ) with NO Math.exp on the hot path.
 * UC-005; BR-021.
 *
 * u = Δt/τ:
 *   u <= 1/128 → α ≈ u        (first-order Taylor, hot path, no LUT lookup)
 *   1/128 < u < 8 → LUT(512) + linear interpolation
 *   u >= 8 → α = 1            (fully decayed)
 */
public final class EwmaAlpha {
    static final int LUT_SIZE = 512;
    static final double U_MAX = 8.0;
    static final double STEP = U_MAX / LUT_SIZE;       // 0.015625
    static final double INV_STEP = 1.0 / STEP;
    static final float[] EXP_LUT = new float[LUT_SIZE + 1];

    static {
        for (int i = 0; i <= LUT_SIZE; i++) {
            EXP_LUT[i] = (float) Math.exp(-i * STEP);
        }
    }

    private EwmaAlpha() {}

    /** Returns α = 1 - exp(-Δt/τ), never calling Math.exp per request. */
    public static float alpha(long dtMs, double tauMs) {
        if (dtMs <= 0) {
            return 0.0f; // same-instant samples do not decay
        }
        if (tauMs <= 0) {
            return 1.0f; // degenerate τ → full decay
        }
        double u = dtMs / tauMs;
        if (u <= 1.0 / 128.0) {
            return (float) u; // first-order, hot path
        }
        if (u >= U_MAX) {
            return 1.0f; // fully decayed
        }
        double x = u * INV_STEP;
        int idx = (int) x;
        double f = x - idx;
        double e = EXP_LUT[idx] * (1 - f) + EXP_LUT[idx + 1] * f; // linear interpolation
        return (float) (1 - e);
    }
}
