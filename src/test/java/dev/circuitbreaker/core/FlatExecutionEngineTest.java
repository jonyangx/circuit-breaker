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
        int rid = ResourceManager.register(
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
        int rid = ResourceManager.register(
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
        int rid = ResourceManager.register(
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

    @Test
    void releaseRejectsInvalidResourceId() {
        int validRid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        long token = FlatExecutionEngine.tryAcquire(validRid);

        // resourceId out of range should throw IllegalArgumentException
        assertThatThrownBy(() -> FlatExecutionEngine.release(-1, token, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceId out of range");

        // unregistered resourceId (in range but null STATES slot) should throw
        // Slot 1023 is in range and almost certainly null (sequential allocation tops at <1024)
        assertThatThrownBy(() -> FlatExecutionEngine.release(1023, token, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unregistered resourceId");

        // Even blocked tokens are validated first (consistent with tryAcquire semantics)
        assertThatThrownBy(() -> FlatExecutionEngine.release(-1, BlockCode.CONCURRENCY, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceId out of range");
    }

    @Test
    void releaseDetectsCrossResourceTokenMismatch() {
        // BR-053: embedded resourceId defends against cross-resource release bugs.
        int ridA = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1));
        int ridB = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 100, 1));

        long tokenA = FlatExecutionEngine.tryAcquire(ridA);

        // Releasing resource A's token with resourceId=B must fail (would corrupt B's counters)
        assertThatThrownBy(() -> FlatExecutionEngine.release(ridB, tokenA, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token/resourceId mismatch")
                .hasMessageContaining("resource " + ridA)
                .hasMessageContaining("resourceId=" + ridB);

        // Releasing with correct resourceId must succeed
        FlatExecutionEngine.release(ridA, tokenA, true);
    }
}
