package dev.circuitbreaker.reactive;

import dev.circuitbreaker.core.BlockCode;

/**
 * Thrown (as Mono.error) when a governance decision blocks the call (UC-009).
 * Carries the negative block code so callers can distinguish rate-limit / breaker / concurrency / overload.
 */
public class CircuitBreakerBlockedException extends RuntimeException {

    private final long blockCode;

    public CircuitBreakerBlockedException(long blockCode) {
        super(describe(blockCode));
        this.blockCode = blockCode;
    }

    public long getBlockCode() {
        return blockCode;
    }

    private static String describe(long code) {
        if (code == BlockCode.SYSTEM_OVERLOAD) return "system overload";
        if (code == BlockCode.CIRCUIT_BREAKER) return "circuit breaker open";
        if (code == BlockCode.RATE_LIMITER) return "rate limited";
        if (code == BlockCode.CONCURRENCY) return "concurrency limit exceeded";
        return "blocked: " + code;
    }
}
