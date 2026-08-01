package dev.circuitbreaker.fault;

import dev.circuitbreaker.core.BlockCode;
import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import dev.circuitbreaker.core.ResourceState;
import dev.circuitbreaker.core.breaker.EwmaCircuitBreaker;
import dev.circuitbreaker.core.ratelimit.LazyTokenBucket;
import dev.circuitbreaker.core.system.SystemOverload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fault-injection tests: inject failures / overload / saturation / stale state and verify the
 * governance response. Time-sensitive faults (breaker, rate-limit) drive the capability classes
 * with an explicit monotonic clock (nowMs) for determinism; time-insensitive faults go through
 * the engine with the real clock.
 */
class FaultInjectionTest {

    @AfterEach
    void resetOverload() {
        SystemOverload.setShedPermilleForTest(0);   // SHED_PERMILLE is global static
    }

    // ---- breaker: inject continuous failures → trip → recover ----

    @Test
    void continuousFailuresTripBreakerThenProbeRecovers() {
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);                 // CLOSED
        for (int i = 1; i <= 5; i++) {                             // inject 100% failures, τ-spaced
            EwmaCircuitBreaker.release(st, i * 1000L, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 5500)).isFalse();   // tripped → blocked
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isTrue();    // past open window → probe
        EwmaCircuitBreaker.release(st, 7000, true, cfg, true);    // probe succeeds → recovers
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7001)).isTrue();    // CLOSED → passes again
    }

    @Test
    void lostProbeSelfHealsAfterGrace() {
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 5, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.release(st, i * 1000L, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isTrue();   // probe elected (deadline 8000)
        // inject fault: probe never releases
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7500)).isFalse();  // before deadline → blocked
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 9000)).isFalse();  // past deadline → re-armed, blocked
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 10500)).isTrue();  // fresh probe after re-arm window
        EwmaCircuitBreaker.release(st, 10500, true, cfg, true);              // recovers
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 10501)).isTrue();  // passes again
    }

    // ---- rate limit: inject a burst exceeding capacity ----

    @Test
    void rateLimitBurstExceedsCapacityThenRefills() {
        ResourceConfig cfg = new ResourceConfig(0x02, 1000, 1000, 0, 1, 1000, 1000, 0, 1);
        ResourceState st = new ResourceState();
        int passed = 0;
        for (int i = 0; i < 1000; i++) {                           // burst drains the bucket
            if (LazyTokenBucket.tryAcquire(st, cfg, 1000L)) passed++;
        }
        assertThat(passed).isEqualTo(1000);
        assertThat(LazyTokenBucket.tryAcquire(st, cfg, 1000L)).isFalse();  // over rate → blocked
        assertThat(LazyTokenBucket.tryAcquire(st, cfg, 2000L)).isTrue();   // +1s → refill (dtMs/1000=1)
    }

    // ---- system overload: inject shed level ----

    @Test
    void systemOverloadInjectionDropsAllOrNone() {
        int rid = ResourceManager.register("fault-overload",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));
        SystemOverload.setShedPermilleForTest(1000);               // inject: shed everything
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isEqualTo(BlockCode.SYSTEM_OVERLOAD);
        SystemOverload.setShedPermilleForTest(0);                  // inject: shed nothing
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0);
    }

    // ---- concurrency: inject saturation ----

    @Test
    void concurrencySaturationFault() {
        int rid = ResourceManager.register("fault-conc",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 3, 1));
        long a = FlatExecutionEngine.tryAcquire(rid);
        long b = FlatExecutionEngine.tryAcquire(rid);
        long c = FlatExecutionEngine.tryAcquire(rid);
        assertThat(a).isGreaterThanOrEqualTo(0);
        assertThat(b).isGreaterThanOrEqualTo(0);
        assertThat(c).isGreaterThanOrEqualTo(0);
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isEqualTo(BlockCode.CONCURRENCY); // saturated → -4
        FlatExecutionEngine.release(rid, a, true);                 // drain one
        assertThat(FlatExecutionEngine.tryAcquire(rid)).isGreaterThanOrEqualTo(0);         // freed
    }

    // ---- hot-reload: inject a stale-version release ----

    @Test
    void staleVersionReleaseFaultIsGraceful() {
        int rid = ResourceManager.register("fault-stale",
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 1));
        long token = FlatExecutionEngine.tryAcquire(rid);
        // inject: config swapped while token in flight
        ResourceManager.publishConfig(rid,
                new ResourceConfig(0x02, 1_000_000, 1_000_000, 0, 1, 1000, 1000, 0, 2));
        // releasing the stale token must not throw and must leave no negative drift
        FlatExecutionEngine.release(rid, token, true);
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
    }
}
