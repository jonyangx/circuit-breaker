package dev.circuitbreaker.core.ratelimit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 惰性令牌桶测试（UC-004，BR-010/011/012/013）。
 * 关联测试案例：TC-CAP-RL-001..004。
 */
class LazyTokenBucketTest {
    @Test void rejectsOverRate() { fail("TODO: rate=1000/ms 以 2000/ms 调用约半数阻断、不超额（BR-011）"); }
    @Test void capacityCapped() { fail("TODO: capacity 截断（BR-011 min）"); }
    @Test void lowRateNoFloatZeroOut() { fail("TODO: 1/s 资源令牌能生成（BR-013）"); }
    @Test void timeLastAdvanceOnlyOnFullToken() { fail("TODO: 仅完整令牌推进 Time_last（BR-013）"); }
}
