package dev.circuitbreaker.reactive;

import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.GovernanceException;
import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Reactive governance tests (UC-009; BR-060/061). TC-API-006-001/002. */
class CircuitBreakerOperatorTest {

    @Test
    void successPathReleasesOnReactorThread() {
        int rid = ResourceManager.register("rx-ok",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.just("value")))
                .expectNext("value")
                .verifyComplete();

        // release happened in the reactor pipeline → concurrency rolled back to zero
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
    }

    @Test
    void blockedCallPropagatesError() {
        int rid = ResourceManager.register("rx-block",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1, 1));
        long held = FlatExecutionEngine.tryAcquire(rid); // exhaust the single slot and hold it
        assertThat(held).isGreaterThanOrEqualTo(0);

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.just("never")))
                .expectError(GovernanceException.class)
                .verify();

        FlatExecutionEngine.release(rid, held, true); // cleanup
    }

    @Test
    void errorPathStillReleases() {
        int rid = ResourceManager.register("rx-err",
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.error(new IllegalStateException("boom"))))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero(); // released via doOnError
    }
}
