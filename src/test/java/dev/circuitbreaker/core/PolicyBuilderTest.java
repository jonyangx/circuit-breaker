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
        // qps above the 22-bit token-field capacity must be rejected (would overflow the bucket)
        assertThatThrownBy(() -> new PolicyBuilder().enableRateLimit(5_000_000L).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyBuilder().enableConcurrency(0).openMillis(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- opt-in SLA invariant enforcement (PolicySpec integration) ----

    private static final PolicySpec.SlaFacts SLA =
            new PolicySpec.SlaFacts(2000, 50, 100, 1000);

    @Test
    void slaCheckPassesForHealthyPolicy() {
        ResourceConfig c = new PolicyBuilder()
                .enableRateLimit(1600)
                .enableCircuitBreaker(0.10f)
                .minimumCalls(50)
                .ewmaHalfLife(5_000)
                .openMillis(10_000)
                .enableConcurrency(160)
                .sla(SLA)
                .build();
        assertThat(c.qps).isEqualTo(1600);
    }

    @Test
    void slaCheckRejectsErrorLevelViolation() {
        // qps == slaTps → S1 ERROR (no headroom); builder's own checks pass, SLA check rejects.
        assertThatThrownBy(() -> new PolicyBuilder()
                .enableRateLimit(2000)
                .enableCircuitBreaker(0.10f)
                .minimumCalls(50)
                .ewmaHalfLife(5_000)
                .enableConcurrency(160)
                .sla(SLA)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SLA invariants");
    }

    @Test
    void slaCheckIsOptInWithoutSlaFacts() {
        // Same qps==slaTps (S1 ERROR from the SLA view), but sla() NOT called → build succeeds.
        ResourceConfig c = new PolicyBuilder()
                .enableRateLimit(2000)
                .enableCircuitBreaker(0.10f)
                .minimumCalls(50)
                .ewmaHalfLife(5_000)
                .enableConcurrency(160)
                .build();
        assertThat(c.qps).isEqualTo(2000);
    }
}
