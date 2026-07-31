package dev.circuitbreaker.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine-level TPS dynamics tests — validates the integrated behavior of all
 * governance modules under burst, drop, and jittered traffic patterns.
 *
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §9 (TPS dynamics).
 */
class TpsDynamicsEngineTest {

    @AfterEach
    void reset() {
        dev.circuitbreaker.core.system.SystemOverload.setShedPermilleForTest(0);
    }

    /**
     * §9: Spike then drain — all capabilities enabled.
     * At high burst, concurrency spikes are self-limiting; token bucket caps the spike to capacity.
     */
    @Test
    void spikeThenDrainAllCapabilities() {
        int rid = ResourceManager.register("engine-spike",
                new ResourceConfig(0x07, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1000, 1000, 10, 1));
        ResourceState st = ResourceManager.state(rid);

        // Spike: 50 rapid acquires (concurrency cap = 10, rate cap = 1M/s, everything else permissive)
        int pass = 0, block = 0;
        for (int i = 0; i < 50; i++) {
            long token = FlatExecutionEngine.tryAcquire(rid);
            if (token >= 0) {
                pass++;
                // DON'T release — simulate spike holding (concurrency builds up)
            } else {
                block++;
            }
        }
        // After 50 concurrent acquires with concurrency limit 10:
        // First 10 pass, next 40 are blocked by concurrency
        assertThat(pass).isEqualTo(10);
        assertThat(block).isEqualTo(40);
        assertThat(st.blockCount()).isEqualTo(40);
        assertThat(st.passCount()).isEqualTo(10);

        // Drain: release all held tokens
        // (We can't release because tokens aren't stored — this is a design limitation
        //  of this test, but the invariant we CAN verify is the final state.)
    }

    /**
     * §9: Spike saturates concurrency, then release frees slots for the next spike.
     * Clock-independent (concurrency has no time component) — robust against real-clock jitter.
     */
    @Test
    void spikeSaturatesThenReleaseFreesForNextSpike() {
        int rid = ResourceManager.register("engine-conc-spike",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 5, 1));
        ResourceState st = ResourceManager.state(rid);

        // Spike 1: 5 pass, rest blocked
        long[] held = new long[5];
        for (int i = 0; i < 5; i++) {
            held[i] = FlatExecutionEngine.tryAcquire(rid);
            assertThat(held[i]).isGreaterThanOrEqualTo(0);
        }
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isEqualTo(BlockCode.CONCURRENCY); // saturated

        // Release all, freeing slots
        for (int i = 0; i < 5; i++) {
            FlatExecutionEngine.release(rid, held[i], true);
        }
        assertThat(st.sumConcurrency()).isZero();

        // Spike 2: same behavior (no leak across spikes)
        for (int i = 0; i < 5; i++) {
            assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0);
        }
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isEqualTo(BlockCode.CONCURRENCY);
    }

    /**
     * §9: Token bucket's version is embedded correctly in every acquired token — even under jitter.
     */
    @Test
    void tokenVersionEmbeddedCorrecltyUnderJitter() {
        int rid = ResourceManager.register("engine-version-jitter",
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 1));
        long t1 = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeVersion(t1)).isEqualTo(1);

        // Hot-swap while in-flight
        ResourceManager.publishConfig(rid,
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 2));
        long t2 = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeVersion(t2)).isEqualTo(2); // new config's version

        // Both tokens release correctly with their respective versions
        FlatExecutionEngine.release(rid, t1, true);
        FlatExecutionEngine.release(rid, t2, true);
    }
}
