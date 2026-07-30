package dev.circuitbreaker.reactive;

import reactor.core.publisher.Mono;
import java.util.function.Supplier;

/**
 * Reactor/WebFlux 响应式治理包装（BR-060/061）。
 * 关联用例：UC-009。
 * 实现步骤：Mono.defer→tryAcquire；token<0→Mono.error；否则 source 上挂 doOnSuccess/doOnError→release。
 *   - release 从 token 解 version+bucketIdx，与执行 Reactor 线程无关（线程无关 BR-060）。
 */
public final class CircuitBreakerOperator {
    public static <T> Mono<T> wrap(int resourceId, Supplier<Mono<T>> source) {
        throw new UnsupportedOperationException("TODO: defer→acquire→error|doOnSuccess/doOnError release（UC-009/BR-060）");
    }
}
