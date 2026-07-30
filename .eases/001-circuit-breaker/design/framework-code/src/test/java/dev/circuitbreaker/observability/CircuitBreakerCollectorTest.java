package dev.circuitbreaker.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Prometheus 导出测试（UC-010，BR-070/071/072）。
 * 关联测试案例：TC-API-007-001..003。
 */
class CircuitBreakerCollectorTest {
    @Test void counterMonotonic() { fail("TODO: pass/block 差值非负，禁 reset（BR-071）"); }
    @Test void ewmaGaugeConverges() { fail("TODO: Gauge=ppm/1e6 随错误率收敛（BR-072）"); }
    @Test void scrapeNonBlocking() { fail("TODO: scrape 期间 acquire/release 无尾延迟劣化（BR-070）"); }
}
