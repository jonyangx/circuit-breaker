package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PolicyBuilder composition + validation tests (UC-001). */
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

    @Test
    void rejectsInvalidConfig() {
        // breaker threshold out of (0,1] → always-trip / never-trip footguns rejected
        assertThatThrownBy(() -> new PolicyBuilder().enableCircuitBreaker(0f).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyBuilder().enableCircuitBreaker(1.5f).build())
                .isInstanceOf(IllegalArgumentException.class);
        // τ / minCalls / openMillis / qps / concurrency must be positive
        assertThatThrownBy(() -> new PolicyBuilder().enableCircuitBreaker(0.5f).ewmaHalfLife(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyBuilder().enableRateLimit(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyBuilder().enableConcurrency(0).openMillis(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
