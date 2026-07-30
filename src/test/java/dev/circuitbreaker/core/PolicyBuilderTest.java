package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PolicyBuilder composition tests (UC-001). */
class PolicyBuilderTest {

    @Test
    void composesMaskAndDerivesParams() {
        ResourceConfig c = new PolicyBuilder()
                .enableRateLimit(1000)
                .enableCircuitBreaker(0.5f)
                .minimumCalls(20)
                .ewmaHalfLife(5000)
                .openMillis(3000)
                .enableConcurrency(50)
                .build();
        assertThat(c.mask).isEqualTo(0x07);
        assertThat(c.qps).isEqualTo(1000);
        assertThat(c.capacity).isEqualTo(1000);
        assertThat(c.errThresholdPpm).isEqualTo(500_000);
        assertThat(c.minCalls).isEqualTo(20);
        assertThat(c.ewmaTauMs).isEqualTo(5000);
        assertThat(c.openMillis).isEqualTo(3000);
        assertThat(c.concurrencyLimit).isEqualTo(50);
        assertThat(c.version).isEqualTo(1);
    }
}
