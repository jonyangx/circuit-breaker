package dev.circuitbreaker.reactive;

import dev.circuitbreaker.core.FlatExecutionEngine;
import dev.circuitbreaker.core.GovernanceException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.function.Supplier;

/**
 * Reactor/WebFlux governance wrapper (UC-009; BR-060/061).
 * acquire in Mono.defer; on block → Mono.error; otherwise attach doFinally → release on all termination signals.
 * The token is a long captured by the closure, so release is thread-agnostic regardless of which
 * Reactor thread executes it — no ThreadLocal binding. Block codes surface via the unified
 * {@link GovernanceException} hierarchy (single source of truth shared with the sync engine).
 *
 * <p>P0 fix: doOnSuccess/doOnError → doFinally ensures CANCEL signals also release slots.
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
                    .doFinally(signal -> {
                        boolean success = signal == SignalType.ON_COMPLETE;
                        FlatExecutionEngine.release(resourceId, token, success);
                    });
        });
    }
}
