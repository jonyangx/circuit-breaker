package dev.circuitbreaker.observability;

import dev.circuitbreaker.core.ResourceManager;
import dev.circuitbreaker.core.ResourceState;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prometheus collector for governance metrics (UC-010; BR-070/071/072).
 * Emits monotonic counters (pass/block — caller computes deltas via scrape, never reset) and an
 * EWMA error-rate gauge (ppm / 1e6). Reads only — does not touch the hot path.
 */
public final class CircuitBreakerCollector extends Collector {

    private final int[] resourceIds;

    public CircuitBreakerCollector(int... resourceIds) {
        this.resourceIds = resourceIds;
    }

    /** Convenience: construct and register against a registry. */
    public static void register(CollectorRegistry registry, int... resourceIds) {
        new CircuitBreakerCollector(resourceIds).register(registry);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> out = new ArrayList<>(2);

        List<MetricFamilySamples.Sample> calls = new ArrayList<>();
        for (int id : resourceIds) {
            ResourceState st = ResourceManager.state(id);
            if (st == null) {
                continue;
            }
            calls.add(new MetricFamilySamples.Sample(
                    "circuit_breaker_calls_total",
                    List.of("resource", "result"),
                    List.of(String.valueOf(id), "pass"),
                    st.passCount()));
            calls.add(new MetricFamilySamples.Sample(
                    "circuit_breaker_calls_total",
                    List.of("resource", "result"),
                    List.of(String.valueOf(id), "block"),
                    st.blockCount()));
        }
        out.add(new MetricFamilySamples(
                "circuit_breaker_calls_total", Type.COUNTER,
                "Total governance decisions (pass/block); monotonic — scrape computes deltas.",
                calls));

        List<MetricFamilySamples.Sample> errorRate = new ArrayList<>();
        for (int id : resourceIds) {
            ResourceState st = ResourceManager.state(id);
            if (st == null) {
                continue;
            }
            errorRate.add(new MetricFamilySamples.Sample(
                    "circuit_breaker_error_rate",
                    Collections.singletonList("resource"),
                    Collections.singletonList(String.valueOf(id)),
                    st.ewmaErrorRatePpm() / 1_000_000.0));
        }
        out.add(new MetricFamilySamples(
                "circuit_breaker_error_rate", Type.GAUGE,
                "Time-decay EWMA error rate (0..1).",
                errorRate));

        return out;
    }
}
