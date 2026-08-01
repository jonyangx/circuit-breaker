package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FlatExecutionEngine end-to-end via time-independent paths (UC-002/003/006; BR-005). */
class FlatExecutionEngineTest {

    @Test
    void unregisteredResourceThrows() {
        assertThatThrownBy(() -> FlatExecutionEngine.tryAcquire(9999))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrencyBlocksAndReleasesViaEngine() {
        int rid = ResourceManager.register("conc",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 2, 1));
        long t1 = FlatExecutionEngine.tryAcquire(rid);
        long t2 = FlatExecutionEngine.tryAcquire(rid);
        assertThat(t1).isGreaterThanOrEqualTo(0);
        assertThat(t2).isGreaterThanOrEqualTo(0);
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isEqualTo(BlockCode.CONCURRENCY); // 3rd blocked
        FlatExecutionEngine.release(rid, t1, true);
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0); // freed
        // token carries mask + version
        assertThat(TokenCodec.decodeMask(t1)).isEqualTo(0x04);
        assertThat(TokenCodec.decodeVersion(t1)).isEqualTo(1);
    }

    @Test
    void systemOverloadShortCircuitsBeforePolicy() {
        int rid = ResourceManager.register("ovl",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        dev.circuitbreaker.core.system.SystemOverload.setShedPermilleForTest(1000);
        try {
            assertThat(FlatExecutionEngine.tryAcquire(rid)).isEqualTo(BlockCode.SYSTEM_OVERLOAD);
            assertThat(ResourceManager.state(rid).blockCount()).isEqualTo(1);
        } finally {
            dev.circuitbreaker.core.system.SystemOverload.setShedPermilleForTest(0);
        }
    }

    @Test
    void releaseWithBlockedTokenIsNoOp() {
        // A blocked token (< 0) carries no resource state; release() must be a no-op,
        // never touching concurrency/breaker counters (BR-004).
        int rid = ResourceManager.register("blocked-release",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        long beforeSum = ResourceManager.state(rid).sumConcurrency();
        long beforeBlock = ResourceManager.state(rid).blockCount();
        // releasing each block code must not change any counter
        FlatExecutionEngine.release(rid, BlockCode.SYSTEM_OVERLOAD, true);
        FlatExecutionEngine.release(rid, BlockCode.CIRCUIT_BREAKER, false);
        FlatExecutionEngine.release(rid, BlockCode.RATE_LIMITER, true);
        FlatExecutionEngine.release(rid, BlockCode.CONCURRENCY, false);
        assertThat(ResourceManager.state(rid).sumConcurrency()).isEqualTo(beforeSum);
        assertThat(ResourceManager.state(rid).blockCount()).isEqualTo(beforeBlock);
    }
}
