package dev.circuitbreaker.core;

/**
 * Release outcome — the tri-state result of a governed call (AA Defect 1 fix).
 *
 * <p>{@link #SUCCESS} and {@link #FAILURE} feed the circuit-breaker EWMA (a completed call
 * observed the downstream's true health). {@link #CANCELLED} covers calls that were aborted
 * before reaching the downstream (e.g. a reactive subscriber cancelling the subscription):
 * the concurrency slot MUST still be released, but the cancellation carries no health signal,
 * so it must NOT be counted as a failure — otherwise a client that subscribes and immediately
 * cancels can inflate the error rate and trip the breaker (availability attack, AA Defect 1).</p>
 *
 * <p>Replaces the bare {@code boolean success} in the reactive path only; the sync
 * {@link FlatExecutionEngine#release(int, long, boolean)} overload remains for callers that
 * have exactly two outcomes.</p>
 */
public enum Outcome {
    /** Call completed normally — downstream observed success. */
    SUCCESS,
    /** Call completed exceptionally — downstream observed failure. */
    FAILURE,
    /** Call was cancelled before completion — no health signal, but the slot must be freed. */
    CANCELLED
}
