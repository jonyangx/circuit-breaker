package dev.circuitbreaker.core.breaker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * α 分段近似测试（UC-005，BR-021）。
 * 关联测试案例：TC-CAP-CB-006。
 */
class EwmaAlphaTest {
    @Test void smallUFirstOrder() { fail("TODO: u<=1/128 → α≈u（热路径）"); }
    @Test void midUInterpolationError() { fail("TODO: u∈(1/128,8) 绝对误差<=~3e-5（ppm 级）"); }
    @Test void largeUSaturated() { fail("TODO: u>=8 → α=1"); }
}
