package dev.circuitbreaker.core.breaker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EWMA 三态状态机 + 代际测试（UC-005，BR-020/024/025）。
 * 关联测试案例：TC-CAP-CB-001..005。
 */
class EwmaCircuitBreakerTest {
    @Test void tripsOnHighErrorRate() { fail("TODO: 连续失败→OPEN（BR-023/025）"); }
    @Test void halfOpenSingleProbe() { fail("TODO: OPEN 到期唯一线程→HALF_OPEN 探路（BR-025）"); }
    @Test void recoverOnProbeSuccess() { fail("TODO: 探路成功→CLOSED（BR-025）"); }
    @Test void generationPreventsReTrip() { fail("TODO: HALF_OPEN→CLOSED 后旧 ppm 不二次跳闸（BR-024 代际重播种）"); }
    @Test void reOpenOnProbeFailure() { fail("TODO: 探路失败→重 OPEN（BR-025）"); }
}
