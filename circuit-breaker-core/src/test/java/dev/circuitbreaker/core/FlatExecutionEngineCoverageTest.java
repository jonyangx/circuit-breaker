package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Additional engine coverage: all-capability acquire/release + rate-limit-only path (UC-002/003). */
class FlatExecutionEngineCoverageTest {

    @Test
    void acquireReleaseAllCapabilitiesViaEngine() {
        // mask 0x07; breaker threshold unreachable (100%), high rate/concurrency so nothing blocks
        int rid = ResourceManager.register("all",
                new ResourceConfig(0x07, 1_000_000, 1_000_000, 1_000_000, 1_000, 1000, 1000, 1_000_000, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);
        assertThat(token).isGreaterThanOrEqualTo(0);
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(0x07);
        // release success exercises concurrency rollback + breaker release(success) CLOSED path
        FlatExecutionEngine.release(rid, token, true);
        long token2 = FlatExecutionEngine.tryAcquire(rid);
        FlatExecutionEngine.release(rid, token2, false); // breaker release(failure)
        assertThat(ResourceManager.state(rid).passCount()).isGreaterThanOrEqualTo(2);
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero(); // all rolled back
    }

    @Test
    void rateLimitOnlyAcquireRelease() {
        int rid = ResourceManager.register("rl-only",
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);
        assertThat(token).isGreaterThanOrEqualTo(0);
        // release with mask 0x02 → neither concurrency nor breaker branch taken
        FlatExecutionEngine.release(rid, token, true);
    }
}
