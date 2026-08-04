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
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.just("value")))
                .expectNext("value")
                .verifyComplete();

        // release happened in the reactor pipeline → concurrency rolled back to zero
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
    }

    @Test
    void blockedCallPropagatesError() {
        int rid = ResourceManager.register(
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
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.error(new IllegalStateException("boom"))))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero(); // released via doFinally
    }

    @Test
    void cancelledSubscriptionReleasesSlotWithoutTrippingBreaker() {
        // CB + CC resource: an adversarial client that subscribes and immediately cancels must
        // release the concurrency slot (else it leaks forever) AND must not feed the breaker EWMA
        // as a failure (else subscribe-then-cancel inflates the error rate and false-trips the
        // breaker — AA Defect 1 availability attack).
        int rid = ResourceManager.register(
                new ResourceConfig(0x05, 0, 0, 500_000, 5, 1000, 1000, 2, 1));

        for (int i = 0; i < 50; i++) {
            StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> Mono.never()))
                    .expectSubscription()
                    .thenCancel()
                    .verify();
        }

        // Every cancellation released its slot — nothing leaked.
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
        // CANCELLED carries no health signal: 50 cancels left the EWMA error rate at 0, so the
        // breaker is still CLOSED and will serve real traffic normally.
        assertThat(ResourceManager.state(rid).ewmaErrorRatePpm()).isZero();
    }

    /**
     * P1 fix: a supplier that throws synchronously (e.g. input validation) never produces a Mono,
     * so doFinally never fires. Without the try-catch in {@code wrap()} the concurrency slot leaks
     * forever. The slot must be released as CANCELLED (no health signal) and the original exception
     * propagated.
     */
    @Test
    void synchronousSupplierExceptionReleasesSlot() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> {
                    throw new IllegalArgumentException("invalid input");
                }))
                .expectError(IllegalArgumentException.class)
                .verify();

        // The slot was released even though the supplier never returned a Mono.
        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
    }

    /**
     * P1 fix, symmetric: a supplier that throws a subclass of Error must also release the slot.
     * Errors (e.g. AssertionError in a buggy supplier) are equally capable of skipping doFinally.
     */
    @Test
    void synchronousSupplierErrorReleasesSlot() {
        int rid = ResourceManager.register(
                new ResourceConfig(0x04, 0, 0, 0, 1, 1000, 1000, 1_000_000, 1));

        StepVerifier.create(CircuitBreakerOperator.wrap(rid, () -> {
                    throw new AssertionError("internal invariant broken");
                }))
                .expectError(AssertionError.class)
                .verify();

        assertThat(ResourceManager.state(rid).sumConcurrency()).isZero();
    }
}
