package dev.circuitbreaker.core;

/**
 * Monotonic relative clock (BR-006). nowRelMs() = nanoTime/1M - START.
 * All governance decisions use this (never currentTimeMillis).
 */
public final class ClockSource {
    // START initialized in a static block; guard against pathological values
    private static final long START;

    static {
        START = System.nanoTime() / 1_000_000L;
        if (START == Long.MAX_VALUE || START == Long.MIN_VALUE) {
            throw new IllegalStateException("ClockSource initialization failed");
        }
    }

    private ClockSource() {}

    /** Relative monotonic millisecond timestamp (fits token's 41-bit time field). */
    public static long nowRelMs() {
        long raw = System.nanoTime() / 1_000_000L - START;
        return Math.max(0L, raw); // clamp to prevent negative timestamps
    }
}
