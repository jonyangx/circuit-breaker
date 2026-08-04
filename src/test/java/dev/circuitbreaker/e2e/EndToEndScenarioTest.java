package dev.circuitbreaker.e2e;

import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import dev.circuitbreaker.core.ResourceState;
import dev.circuitbreaker.core.TokenCodec;
import dev.circuitbreaker.observability.CircuitBreakerCollector;
import dev.circuitbreaker.reactive.CircuitBreakerOperator;
import io.prometheus.client.Collector.MetricFamilySamples;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end scenarios through the public API (real clock, time-insensitive flows).
 * Validates the integrated acquire/release lifecycle, concurrency, hot-reload, the reactive
 * pipeline, and Prometheus export — the system as an integrator would use it.
 */
class EndToEndScenarioTest {

    /** Scenario: a resource with all capabilities gates a stream of calls; every acquire is released. */
    @Test
    void fullLifecycleAllCapabilities() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x07, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1000, 1000, 1_000_000, 1));
        ResourceState st = ResourceManager.state(rid);

        for (int i = 0; i < 100; i++) {
            long token = FlatExecutionEngine.tryAcquire(rid);
            assertThat(token).isGreaterThanOrEqualTo(0);       // never blocked (high limits)
            FlatExecutionEngine.release(rid, token, true);
        }
        assertThat(st.passCount()).isEqualTo(100);             // every attempt passed
        assertThat(st.sumConcurrency()).isZero();              // every acquire released → no leak
    }

    /** Scenario: saturate the concurrency cap, then drain — the cap holds and frees on release. */
    @Test
    void concurrencyLimitEndToEnd() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 5, 1));
        // With limit=5 < SEG=16, limitPerSeg=1, so a transient per-segment cap can block an
        // acquire before the global limit is reached. Retry until 5 slots are held.
        long[] held = new long[5];
        int got = 0, attempts = 0;
        while (got < 5) {
            long t = FlatExecutionEngine.tryAcquire(rid);
            if (t >= 0) held[got++] = t;
            org.assertj.core.api.Assertions.assertThat(++attempts).isLessThan(5 * 100);
        }
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isLessThan(0); // 6th blocked (-4)
        for (int i = 0; i < 5; i++) {
            FlatExecutionEngine.release(rid, held[i], true);           // drain
        }
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0); // freed again
    }

    /** Scenario: hot-reload swaps config while a token is in flight; the stale release is handled gracefully. */
    @Test
    void hotReloadMidFlight() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 1));
        long tokenV1 = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeVersion(tokenV1)).isEqualTo(1);

        // hot-swap to version 2 while the v1 token is in flight
        ResourceManager.publishConfig(rid,
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 2));

        FlatExecutionEngine.release(rid, tokenV1, true);   // stale-version release must not throw
        long tokenV2 = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeVersion(tokenV2)).isEqualTo(2);  // new config published
    }

    /** Scenario: the reactive wrapper releases on both success and error paths. */
    @Test
    void reactivePipelineReleasesOnSuccessAndError() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        ResourceState st = ResourceManager.state(rid);

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.just("ok")))
                .expectNext("ok").verifyComplete();

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.error(new IllegalStateException("boom"))))
                .expectError(IllegalStateException.class).verify();

        assertThat(st.sumConcurrency()).isZero();           // both paths released
    }

    /** Scenario: after traffic, the Prometheus collector exposes monotonic counters + a valid error-rate gauge. */
    @Test
    void metricsExposedAfterTraffic() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 1));
        long t = FlatExecutionEngine.tryAcquire(rid);
        FlatExecutionEngine.release(rid, t, true);
        FlatExecutionEngine.tryAcquire(rid);

        List<MetricFamilySamples> samples = new CircuitBreakerCollector(rid).collect();
        double pass = samples.stream().filter(s -> s.name.equals("circuit_breaker_calls"))
                .flatMap(s -> s.samples.stream()).filter(s -> s.labelValues.contains("pass"))
                .mapToDouble(s -> s.value).sum();
        double err = samples.stream().filter(s -> s.name.equals("circuit_breaker_error_rate"))
                .flatMap(s -> s.samples.stream()).mapToDouble(s -> s.value).max().orElse(-1);
        assertThat(pass).isGreaterThan(0);                  // monotonic counter
        assertThat(err).isBetween(0.0, 1.0);                // gauge in [0,1]
    }
}
