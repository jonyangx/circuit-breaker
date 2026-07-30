package dev.circuitbreaker.core;

/**
 * Unchecked exception hierarchy for governance block decisions (UC-002; BR-004).
 * {@link #throwFor(long)} maps a negative token (block code) to the matching subtype, so callers can
 * turn a blocked acquire into a typed exception in one line:
 *
 * <pre>{@code
 * long token = FlatExecutionEngine.tryAcquire(rid);
 * if (token < 0) {
 *     throw GovernanceException.throwFor(token);
 * }
 * }</pre>
 *
 * Block decisions stay allocation-free on the hot path (returned as a negative long); this exception
 * is only created when the caller chooses to materialize it, off the measurement path.
 */
public abstract class GovernanceException extends RuntimeException {

    private final long blockCode;

    protected GovernanceException(long blockCode, String message) {
        super(message);
        this.blockCode = blockCode;
    }

    public long getBlockCode() {
        return blockCode;
    }

    /** Throws the typed exception matching the given block code. */
    public static RuntimeException throwFor(long token) {
        if (token == BlockCode.RATE_LIMITER) throw new RateLimitedException();
        if (token == BlockCode.CIRCUIT_BREAKER) throw new CircuitOpenException();
        if (token == BlockCode.CONCURRENCY) throw new ConcurrencyLimitedException();
        if (token == BlockCode.SYSTEM_OVERLOAD) throw new SystemOverloadedException();
        throw new IllegalStateException("not a block code: " + token);
    }

    /** Rate limiting blocked the call ({@link BlockCode#RATE_LIMITER}). */
    public static final class RateLimitedException extends GovernanceException {
        public RateLimitedException() { super(BlockCode.RATE_LIMITER, "rate limited"); }
    }

    /** Circuit breaker is open ({@link BlockCode#CIRCUIT_BREAKER}). */
    public static final class CircuitOpenException extends GovernanceException {
        public CircuitOpenException() { super(BlockCode.CIRCUIT_BREAKER, "circuit breaker open"); }
    }

    /** Concurrency limit exceeded ({@link BlockCode#CONCURRENCY}). */
    public static final class ConcurrencyLimitedException extends GovernanceException {
        public ConcurrencyLimitedException() { super(BlockCode.CONCURRENCY, "concurrency limit exceeded"); }
    }

    /** System overload graded shedding ({@link BlockCode#SYSTEM_OVERLOAD}). */
    public static final class SystemOverloadedException extends GovernanceException {
        public SystemOverloadedException() { super(BlockCode.SYSTEM_OVERLOAD, "system overloaded"); }
    }
}
