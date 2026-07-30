package dev.circuitbreaker.observability;

import dev.circuitbreaker.core.ResourceManager;
import io.prometheus.client.CollectorRegistry;

/**
 * Prometheus 指标导出（BR-070/071/072）。
 * 关联用例：UC-010。
 * 实现步骤：注册 pass/block Counter + EWMA Gauge；scrape 时只读 STATES[id] 的 LongAdder.sum()（单调，禁 reset）
 *   与 ewmaState ppm（Gauge=ppm/1e6）。不阻塞热路径。
 */
public final class CircuitBreakerCollector {
    public static void register(CollectorRegistry registry, int... resourceIds) {
        throw new UnsupportedOperationException("TODO: 注册 Counter(pass/block)+Gauge(EWMA)，只读 sum()（UC-010/BR-070/071/072）");
    }
}
