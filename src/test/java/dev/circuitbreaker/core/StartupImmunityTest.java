package dev.circuitbreaker.core;

import dev.circuitbreaker.core.system.SystemOverload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup-immunity tests (engine + config level) — only use public API through
 * FlatExecutionEngine and ResourceManager. For package-private member tests see
 * StartupImmunityBreakerTest and StartupImmunityTokenBucketTest.
 *
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §5.
 */
class StartupImmunityTest {

    @AfterEach
    void resetOverload() {
        SystemOverload.setShedPermilleForTest(0);
    }

    // ---- §5.2: Engine-level — cold start with all capabilities, high limits means nothing blocks ----

    @Test
    void startupAllCapabilitiesPassBeforeAnyTrip() {
        int rid = ResourceManager.register("startup-all",
                new ResourceConfig(0x07, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1000, 1000, 1_000_000, 1));
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void startupMinCallsSubMinNeverTripsEvenWithHighErrorRate() {
        // Register with high error threshold but huge minCalls; a few failures must NOT trip.
        int rid = ResourceManager.register("startup-mincalls",
                new ResourceConfig(0x01, 0, 0, 500_000, 100, 1000, 1000, 0, 1));
        long token;
        for (int i = 0; i < 10; i++) {
            token = FlatExecutionEngine.tryAcquire(rid);
            if (token >= 0) FlatExecutionEngine.release(rid, token, false);
        }
        // Still passing (count=10 < minCalls=100)
        token = FlatExecutionEngine.tryAcquire(rid);
        assertThat(token).isGreaterThanOrEqualTo(0);
    }

    // ---- §5.4: SystemOverload probe absent → SHED_PERMILLE=0 → maybeShed always false ----

    @Test
    void overloadProbeAbsentDoesNotBlock() {
        assertThat(SystemOverload.maybeShed()).isFalse();
        SystemOverload.setShedPermilleForTest(0);
        assertThat(SystemOverload.maybeShed()).isFalse();

        int rid = ResourceManager.register("startup-overload",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0);
    }

    // ---- §5.5: Version mismatch — a stale release from a prior config is harmless ----

    @Test
    void startupStaleVersionReleaseDoesNotCorruptState() {
        int rid = ResourceManager.register("startup-stale",
                new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(1);

        // hot-swap while token in flight
        ResourceManager.publishConfig(rid,
                new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 2));

        // release stale token — version mismatch must not throw
        FlatExecutionEngine.release(rid, token, false);
    }

    // ---- §5.5: Config validation — illegal configs rejected at build(), never reach runtime ----

    @Test
    void startupIllegalConfigRejectedByBuilder() {
        assertThatThrownBy(() -> new PolicyBuilder().openMillis(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("openMillis");
        assertThatThrownBy(() -> new PolicyBuilder().enableRateLimit(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("qps");
        assertThatThrownBy(() -> new PolicyBuilder().enableRateLimit(10_000_000).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token field");
        assertThatThrownBy(() -> new PolicyBuilder().enableCircuitBreaker(0.0f).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errThreshold");
        assertThatThrownBy(() -> new PolicyBuilder().enableCircuitBreaker(0.5f).ewmaHalfLife(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ewmaHalfLife");
    }

    // ---- §5.1: Engine-level relative timing — startup register + token seed works ----

    @Test
    void startupTokenBucketAvailableImmediately() {
        int rid = ResourceManager.register("startup-bucket",
                new ResourceConfig(0x02, 1000, 1000, 0, 1, 1000, 1000, 0, 1));
        // First acquire must pass (seeded to full capacity)
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0);
    }

    // ---- §5.6: Engine-level — startup with all capabilities, high limits, everything passes ----

    @Test
    void startupAllCapabilitiesTokenCarriesCorrectMask() {
        int rid = ResourceManager.register("startup-mask",
                new ResourceConfig(0x07, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1000, 1000, 1_000_000, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(0x07);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(1);
    }
}
