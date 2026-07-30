package dev.circuitbreaker.core.breaker;

/**
 * α = 1 - exp(-Δt/τ) 的分段近似（无 Math.exp，BR-021）。
 * 关联用例：UC-005；规则 BR-021。
 * 实现步骤：静态初始化 EXP_LUT[513]（exp(-i*STEP), STEP=8/512）；
 *   u≤1/128 → α≈u（热路径，无查表）；1/128<u<8 → LUT+线性插值；u≥8 → α=1。
 */
public final class EwmaAlpha {
    static final int LUT_SIZE = 512;
    static final double U_MAX = 8.0;
    static final double STEP = U_MAX / LUT_SIZE;        // 0.015625
    static final double INV_STEP = 1.0 / STEP;
    static final float[] EXP_LUT = new float[LUT_SIZE + 1];
    static {
        throw new UnsupportedOperationException("TODO: 静态块填 EXP_LUT[i]=(float)Math.exp(-i*STEP)");
    }

    /** 返回 α，全程无 Math.exp（热路径走 α≈u 分支）。 */
    public static float alpha(long dtMs, double tauMs) {
        throw new UnsupportedOperationException("TODO: u=dtMs/tauMs 分段返回（BR-021）");
    }
}
