package dev.circuitbreaker.reactive;

import dev.circuitbreaker.core.FlatExecutionEngine;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * Reactor/WebFlux governance wrapper (UC-009; BR-060/061).
 * acquire in Mono.defer; on block → Mono.error; otherwise attach doOnSuccess/doOnError → release.
 * The token is a long captured by the closure, so release is thread-agnostic regardless of which
 * Reactor thread executes it — no ThreadLocal binding.
 */
public final class CircuitBreakerOperator {

    private CircuitBreakerOperator() {}

    public static <T> Mono<T> wrap(int resourceId, Supplier<Mono<T>> source) {
        return Mono.defer(() -> {
            long token = FlatExecutionEngine.tryAcquire(resourceId);
            if (token < 0) {
                return Mono.error(new CircuitBreakerBlockedException(token));
            }
            return source.get()
                    .doOnSuccess(v -> FlatExecutionEngine.release(resourceId, token, true))
                    .doOnError(e -> FlatExecutionEngine.release(resourceId, token, false));
        });
    }
}
