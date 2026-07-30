package dev.circuitbreaker.core;

/**
 * Block codes — all negative (BR-004). token < 0 means blocked (UC-002).
 * -1 system overload / -2 circuit breaker / -3 rate limiter / -4 concurrency.
 */
public final class BlockCode {
    public static final long SYSTEM_OVERLOAD = -1L;
    public static final long CIRCUIT_BREAKER = -2L;
    public static final long RATE_LIMITER    = -3L;
    public static final long CONCURRENCY      = -4L;

    private BlockCode() {}
}
