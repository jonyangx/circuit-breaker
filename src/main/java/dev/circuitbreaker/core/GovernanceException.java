package dev.circuitbreaker.core;

/**
 * Unchecked exception hierarchy for governance block decisions (UC-002; BR-004). The single source
 * of truth for block-code → exception mapping (used by both the sync engine via {@link #throwFor}
 * and the reactive operator via {@link #forToken}).
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

    private static final long serialVersionUID = 1L;

    private final long blockCode;

    protected GovernanceException(long blockCode, String message) {
        super(message);
        this.blockCode = blockCode;
    }

    public long getBlockCode() {
        return blockCode;
    }

    /**
     * N4: control-flow exceptions must not pay for stack-trace capture. These signal a governance
     * block decision (identifiable via {@link #getBlockCode()}), not an unexpected fault; skipping
     * {@code fillInStackTrace} removes the dominant cost on high-frequency block→throw paths.
     */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    /** Returns the typed exception matching the given block code (for reactive Mono.error, etc.). */
    public static GovernanceException forToken(long token) {
        return switch ((int) token) {
            case (int) BlockCode.RATE_LIMITER -> new RateLimitedException();
            case (int) BlockCode.CIRCUIT_BREAKER -> new CircuitOpenException();
            case (int) BlockCode.CONCURRENCY -> new ConcurrencyLimitedException();
            case (int) BlockCode.SYSTEM_OVERLOAD -> new SystemOverloadedException();
            default -> throw new IllegalStateException("not a block code: " + token);
        };
    }

    /** Throws the typed exception matching the given block code. */
    public static RuntimeException throwFor(long token) {
        throw forToken(token);
    }

    /** Rate limiting blocked the call ({@link BlockCode#RATE_LIMITER}). */
    public static final class RateLimitedException extends GovernanceException {
        private static final long serialVersionUID = 1L;
        public RateLimitedException() { super(BlockCode.RATE_LIMITER, "rate limited"); }
    }

    /** Circuit breaker is open ({@link BlockCode#CIRCUIT_BREAKER}). */
    public static final class CircuitOpenException extends GovernanceException {
        private static final long serialVersionUID = 1L;
        public CircuitOpenException() { super(BlockCode.CIRCUIT_BREAKER, "circuit breaker open"); }
    }

    /** Concurrency limit exceeded ({@link BlockCode#CONCURRENCY}). */
    public static final class ConcurrencyLimitedException extends GovernanceException {
        private static final long serialVersionUID = 1L;
        public ConcurrencyLimitedException() { super(BlockCode.CONCURRENCY, "concurrency limit exceeded"); }
    }

    /** System overload graded shedding ({@link BlockCode#SYSTEM_OVERLOAD}). */
    public static final class SystemOverloadedException extends GovernanceException {
        private static final long serialVersionUID = 1L;
        public SystemOverloadedException() { super(BlockCode.SYSTEM_OVERLOAD, "system overloaded"); }
    }
}
