package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * PolicyBuilder validation defect verification test (AA Report §2.3 MEDIUM).
 * Verifies capacity validation against TOKEN_FIELD_MAX, plus existing param validation.
 */
class PolicyBuilderValidationTest {

    private static final long TOKEN_FIELD_MAX = (1L << 22) - 1; // 4,194,303

    /**
     * AA §2.3: qps (and the capacity it implies) exceeding TOKEN_FIELD_MAX must be rejected.
     *
     * <p>PolicyBuilder binds {@code capacity = qps} (1-second burst default), so there is no way to
     * set capacity independently. A qps > TOKEN_FIELD_MAX is rejected by the qps guard, which also
     * implicitly guards capacity. The capacity guard in build() is defensive (covers a future
     * independent capacity setter); for now this test verifies the qps path.</p>
     */
    @Test
    void rejectsCapacityExceedingTokenFieldMax() {
        // qps within range is accepted (capacity = qps <= TOKEN_FIELD_MAX)
        ResourceConfig ok = new PolicyBuilder().enableRateLimit(100).build();
        assertThat(ok.capacity).isEqualTo(100);

        // qps > TOKEN_FIELD_MAX is rejected
        assertThatThrownBy(() ->
                new PolicyBuilder()
                        .enableRateLimit(TOKEN_FIELD_MAX + 1)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qps must be <= " + TOKEN_FIELD_MAX);
    }

    /**
     * Capacity within TOKEN_FIELD_MAX is accepted.
     */
    @Test
    void acceptsCapacityWithinTokenFieldMax() {
        ResourceConfig cfg = new PolicyBuilder()
                .enableRateLimit(1000)
                .build();
        assertThat(cfg.capacity).isEqualTo(1000);
        assertThat(cfg.qps).isEqualTo(1000);
    }

    /**
     * Boundary: capacity exactly at TOKEN_FIELD_MAX is accepted.
     */
    @Test
    void acceptsCapacityAtBoundary() {
        ResourceConfig cfg = new PolicyBuilder()
                .enableRateLimit(TOKEN_FIELD_MAX)
                .build();
        assertThat(cfg.capacity).isEqualTo(TOKEN_FIELD_MAX);
    }

    /**
     * Existing validation: openMillis must be > 0.
     */
    @Test
    void rejectsNonPositiveOpenMillis() {
        assertThatThrownBy(() ->
                new PolicyBuilder()
                        .enableCircuitBreaker(0.5f)
                        .openMillis(0)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openMillis must be > 0");
    }

    /**
     * Existing validation: errThreshold must be in (0, 1].
     */
    @Test
    void rejectsInvalidErrorThreshold() {
        assertThatThrownBy(() ->
                new PolicyBuilder()
                        .enableCircuitBreaker(0.0f)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errThreshold");

        assertThatThrownBy(() ->
                new PolicyBuilder()
                        .enableCircuitBreaker(1.5f)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errThreshold");
    }

    /**
     * Existing validation: minCalls must be in (0, 65535].
     */
    @Test
    void rejectsInvalidMinCalls() {
        assertThatThrownBy(() ->
                new PolicyBuilder()
                        .enableCircuitBreaker(0.5f)
                        .minimumCalls(0)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumCalls");

        assertThatThrownBy(() ->
                new PolicyBuilder()
                        .enableCircuitBreaker(0.5f)
                        .minimumCalls(70000)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumCalls");
    }

    /**
     * Existing validation: concurrencyLimit must be > 0 when concurrency enabled.
     */
    @Test
    void rejectsNonPositiveConcurrencyLimit() {
        assertThatThrownBy(() ->
                new PolicyBuilder()
                        .enableConcurrency(0)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrencyLimit must be > 0");
    }

    /**
     * Full valid policy builds successfully.
     */
    @Test
    void fullValidPolicyBuilds() {
        ResourceConfig cfg = new PolicyBuilder()
                .enableRateLimit(1000)
                .enableCircuitBreaker(0.5f)
                .minimumCalls(10)
                .ewmaHalfLife(5000)
                .openMillis(1000)
                .enableConcurrency(100)
                .build();

        assertThat(cfg.mask).isEqualTo(0x01 | 0x02 | 0x04);
        assertThat(cfg.version).isEqualTo(1);
    }
}