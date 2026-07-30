package dev.circuitbreaker.observability;

import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import io.prometheus.client.Collector.MetricFamilySamples;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prometheus export tests (UC-010; BR-070/071/072). TC-API-007-001. Asserts on collect() samples directly. */
class CircuitBreakerCollectorTest {

    @Test
    void exposesMonotonicCountersAndErrorGauge() {
        int rid = ResourceManager.register("obs",
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 1));
        long t = FlatExecutionEngine.tryAcquire(rid);
        FlatExecutionEngine.release(rid, t, true);
        FlatExecutionEngine.tryAcquire(rid); // a second pass

        List<MetricFamilySamples> samples = new CircuitBreakerCollector(rid).collect();

        assertThat(samples).isNotEmpty();
        // Prometheus normalizes counter family names (strips trailing "_total"; it is re-appended
        // on text exposition), so the counter family is "circuit_breaker_calls".
        assertThat(samples).extracting(s -> s.name)
                .contains("circuit_breaker_calls", "circuit_breaker_error_rate");

        MetricFamilySamples calls = samples.stream()
                .filter(s -> s.name.equals("circuit_breaker_calls")).findFirst().orElseThrow();
        double pass = calls.samples.stream()
                .filter(s -> s.labelValues.contains("pass")).mapToDouble(s -> s.value).sum();
        double block = calls.samples.stream()
                .filter(s -> s.labelValues.contains("block")).mapToDouble(s -> s.value).sum();

        MetricFamilySamples errorRate = samples.stream()
                .filter(s -> s.name.equals("circuit_breaker_error_rate")).findFirst().orElseThrow();
        double err = errorRate.samples.stream().mapToDouble(s -> s.value).max().orElse(-1);

        assertThat(pass).isGreaterThan(0);                // monotonic counter (BR-070/071)
        assertThat(block).isGreaterThanOrEqualTo(0);
        assertThat(err).isBetween(0.0, 1.0);              // gauge in [0,1] (BR-072)
    }
}
