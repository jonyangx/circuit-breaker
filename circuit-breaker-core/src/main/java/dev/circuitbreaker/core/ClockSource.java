package dev.circuitbreaker.core;

/**
 * Monotonic relative clock (BR-006). nowRelMs() = nanoTime/1M - START.
 * All governance decisions use this (never currentTimeMillis).
 */
public final class ClockSource {
    static long START = System.nanoTime() / 1_000_000L;

    private ClockSource() {}

    /** Relative monotonic millisecond timestamp (fits token's 41-bit time field). */
    public static long nowRelMs() {
        return System.nanoTime() / 1_000_000L - START;
    }
}
