package dev.circuitbreaker.core;

/**
 * 阻断码常量（全负，BR-004）。
 * 关联用例：UC-002（token<0 即阻断）。
 * 语义：-1 系统过载 / -2 熔断 / -3 限流 / -4 并发。
 */
public final class BlockCode {
    public static final long SYSTEM_OVERLOAD   = -1L;
    public static final long CIRCUIT_BREAKER   = -2L;
    public static final long RATE_LIMITER      = -3L;
    public static final long CONCURRENCY       = -4L;
    private BlockCode() {}
}
