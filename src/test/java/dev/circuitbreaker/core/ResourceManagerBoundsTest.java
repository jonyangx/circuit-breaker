package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3 (AA N1 residual): ResourceManager.state()/config() must reject out-of-range ids with a clear
 * IllegalArgumentException — not the raw IndexOutOfBoundsException from AtomicReferenceArray.get,
 * which would leak into external callers (e.g. CircuitBreakerCollector) as a scrape break.
 * In-range but unregistered ids keep returning null (the collector's "skip" contract).
 */
class ResourceManagerBoundsTest {

    @Test
    void negativeIdRejected() {
        assertThatThrownBy(() -> ResourceManager.state(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> ResourceManager.config(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void idAtOrAboveMaxRejected() {
        assertThatThrownBy(() -> ResourceManager.state(ResourceManager.MAX_RESOURCES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> ResourceManager.config(ResourceManager.MAX_RESOURCES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }
    @Test
    void inRangeUnregisteredReturnsNull() {
        // The registry is shared across test classes, so scan for a free slot rather than
        // assuming id 0 (no deregistration exists — slots stay occupied once registered).
        for (int i = 0; i < ResourceManager.MAX_RESOURCES; i++) {
            if (ResourceManager.state(i) == null) {
                assertThat(ResourceManager.config(i)).isNull();
                return;
            }
        }
        throw new AssertionError("registry full — no unregistered slot to probe");
    }
}
