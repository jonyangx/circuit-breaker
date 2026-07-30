package dev.circuitbreaker.reactive;

import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.GovernanceException;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * Reactor/WebFlux governance wrapper (UC-009; BR-060/061).
 * acquire in Mono.defer; on block → Mono.error; otherwise attach doOnSuccess/doOnError → release.
 * The token is a long captured by the closure, so release is thread-agnostic regardless of which
 * Reactor thread executes it — no ThreadLocal binding. Block codes surface via the unified
 * {@link GovernanceException} hierarchy (single source of truth shared with the sync engine).
 */
public final class CircuitBreakerOperator {

    private CircuitBreakerOperator() {}

    public static <T> Mono<T> wrap(int resourceId, Supplier<Mono<T>> source) {
        return Mono.defer(() -> {
            long token = FlatExecutionEngine.tryAcquire(resourceId);
            if (token < 0) {
                return Mono.error(GovernanceException.forToken(token));
            }
            return source.get()
                    .doOnSuccess(v -> FlatExecutionEngine.release(resourceId, token, true))
                    .doOnError(e -> FlatExecutionEngine.release(resourceId, token, false));
        });
    }
}
