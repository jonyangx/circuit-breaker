package dev.circuitbreaker.core.reload;

import dev.circuitbreaker.core.BlockCode;
import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import dev.circuitbreaker.core.ResourceState;
import dev.circuitbreaker.core.TokenCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ConfigSwapper RCU hot-reload tests (UC-008; BR-050/051/052). TC-API-005-001..003. */
class ConfigSwapperTest {

    @Test
    void swapLeavesStateStableAndPublishesNewVersion() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x02, 1000, 1000, 0, 1, 1000, 1000, 0, 1));
        ResourceState before = ResourceManager.state(rid);

        ResourceConfig next = new ResourceConfig(0x02, 2000, 2000, 0, 1, 1000, 1000, 0, 2);
        ConfigSwapper.swap(rid, next);

        assertThat(ResourceManager.state(rid)).isSameAs(before);     // BR-051 state stable
        assertThat(ResourceManager.config(rid).version).isEqualTo(2); // new version published
    }

    @Test
    void inFlightReleaseWithStaleVersionIsHandled() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x02, 100_000, 100_000, 0, 1, 1000, 1000, 0, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);     // version 1
        assertThat(token).isGreaterThanOrEqualTo(0);

        // hot-swap to version 2 while a v1 token is in flight
        ConfigSwapper.swap(rid, new ResourceConfig(0x02, 100_000, 100_000, 0, 1, 1000, 1000, 0, 2));

        // releasing the stale-version token must not throw (BR-052); verMatch=false path
        FlatExecutionEngine.release(rid, token, true);
        // new acquires carry the new version
        long t2 = FlatExecutionEngine.tryAcquire(rid);
        assertThat(TokenCodec.decodeVersion(t2)).isEqualTo(2);
        // no negative concurrency drift from the stale release (no concurrency enabled → sum stays 0)
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
    }

    @Test
    void swapRejectsNonMonotonicVersion() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x02, 1000, 1000, 0, 1, 1000, 1000, 0, 3));
        // equal version → rejected
        assertThatThrownBy(() -> ConfigSwapper.swap(rid,
                new ResourceConfig(0x02, 1000, 1000, 0, 1, 1000, 1000, 0, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be > current version");
        // lower version → rejected
        assertThatThrownBy(() -> ConfigSwapper.swap(rid,
                new ResourceConfig(0x02, 1000, 1000, 0, 1, 1000, 1000, 0, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        // current config must be unchanged after rejection
        assertThat(ResourceManager.config(rid).version).isEqualTo(3);
    }

    @Test
    void swapAcceptsHigherVersion() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x02, 1000, 1000, 0, 1, 1000, 1000, 0, 1));
        ConfigSwapper.swap(rid, new ResourceConfig(0x02, 2000, 2000, 0, 1, 1000, 1000, 0, 4));
        assertThat(ResourceManager.config(rid).version).isEqualTo(4);
    }
}
