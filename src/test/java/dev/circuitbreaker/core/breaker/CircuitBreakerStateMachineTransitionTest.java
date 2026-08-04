package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Circuit breaker state machine transition test (AA Report §5.1 HIGH).
 * Covers all 3-state transitions, generation wrapping, timeout recovery, and error handling.
 */
class CircuitBreakerStateMachineTransitionTest {

    private static final long TAU = 1000L;
    private static final long OPEN = 1000L;

    private static ResourceConfig breakerCfg() {
        return new ResourceConfig(0x01, 0, 0, 500_000, 5, OPEN, TAU, 0, 1);
    }

    /**
     * CLOSED → OPEN transition triggers when error rate exceeds threshold.
     */
    @Test
    void closedToOpenOnHighErrorRate() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Drive to OPEN with 5 failures spaced τ apart
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    /**
     * OPEN → HALF_OPEN transition occurs after openMillis timeout.
     * The first acquire after timeout wins the probe election.
     */
    @Test
    void openToHalfOpenAfterTimeout() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Trip to OPEN (endTime ≈ 6000)
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Before timeout (at 5500): blocked
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 5500)).isFalse();

        // After timeout (at 7000): probe wins → HALF_OPEN
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isTrue();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);

        // Others blocked while probe in flight
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isFalse();
    }

    /**
     * HALF_OPEN → CLOSED on successful probe.
     * Generation bumps, invalidating stale EWMA.
     */
    @Test
    void halfOpenToClosedOnSuccessfulProbe() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Trip to OPEN
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Elect probe → HALF_OPEN
        EwmaCircuitBreaker.tryAcquire(st, cfg, 7000);
        int genBefore = EwmaCircuitBreaker.brGen(st.breakerState.get());

        // Release probe successfully → CLOSED
        EwmaCircuitBreaker.release(st, 7000, true, cfg, true);

        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
        assertThat(EwmaCircuitBreaker.brGen(st.breakerState.get())).isEqualTo(genBefore + 1);
    }

    /**
     * HALF_OPEN → OPEN on failed probe.
     * Re-arms OPEN with new deadline.
     */
    @Test
    void halfOpenToOpenOnFailedProbe() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Trip to OPEN
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Elect probe → HALF_OPEN
        EwmaCircuitBreaker.tryAcquire(st, cfg, 7000);

        // Release probe with failure → OPEN
        EwmaCircuitBreaker.release(st, 7000, false, cfg, true);

        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    /**
     * Lost probe (never released) self-heals after grace period.
     * A1 defense: HALF_OPEN → OPEN re-arms on deadline miss.
     */
    @Test
    void lostProbeSelfHealsAfterGrace() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Trip to OPEN
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Elect probe → HALF_OPEN (deadline ≈ 8000)
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isTrue();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);

        // Probe is never released...
        // Before deadline (7500): still blocked
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7500)).isFalse();

        // At deadline (9000): self-heal → OPEN
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 9000)).isFalse();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);

        // After re-arm + openMillis (10500): fresh probe can win
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 10500)).isTrue();
        EwmaCircuitBreaker.release(st, 10500, true, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    /**
     * Generation prevents immediate re-trip after recovery.
     * BR-024: generation bump invalidates stale EWMA.
     */
    @Test
    void generationPreventsImmediateReTrip() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Trip to OPEN
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Recover via probe
        EwmaCircuitBreaker.tryAcquire(st, cfg, 7000);
        EwmaCircuitBreaker.release(st, 7000, true, cfg, true);

        // One failure right after recovery must NOT re-trip (generation invalidated EWMA)
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
        for (int i = 0; i < 3; i++) { // even 3 failures < minCalls(5)
            EwmaCircuitBreaker.tryAcquire(st, cfg, 7001 + i);
            EwmaCircuitBreaker.release(st, 7001 + i, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    /**
     * Generation wraps at 255 → 0 (8-bit field).
     * Verify state machine remains correct after wrap.
     */
    /**
     * Generation wraps at 255 → 0 (8-bit field).
     * Verify state machine remains correct after wrap.
     *
     * <p>Each transition bumps generation by 1 (mod 256). We drive 300 transitions across
     * 100 CLOSED→OPEN→HALF_OPEN→CLOSED cycles and assert the wrapped value and that the state
     * machine still transitions correctly afterward.</p>
     */
    @Test
    void generationWrapAt255Works() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // 100 cycles × 3 transitions = 300 transitions. 300 & 0xFF = 44 (wraps past 255).
        for (int cycle = 0; cycle < 100; cycle++) {
            long t = 100_000L + cycle * 100L;
            EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.CLOSED, EwmaCircuitBreaker.OPEN, t);
            EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.OPEN, EwmaCircuitBreaker.HALF_OPEN, t + 50);
            EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.HALF_OPEN, EwmaCircuitBreaker.CLOSED, t + 100);
        }

        int genAfterWrap = EwmaCircuitBreaker.brGen(st.breakerState.get());
        assertThat(genAfterWrap).isEqualTo(44); // 300 & 0xFF = 44 (wrapped past 255)
        // State machine should still work after wrap
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);

        // One more transition still bumps generation correctly (44 → 45)
        EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.CLOSED, EwmaCircuitBreaker.OPEN, 200_000L);
        assertThat(EwmaCircuitBreaker.brGen(st.breakerState.get())).isEqualTo(45);
    }

    /**
     * Concurrent transitions from OPEN to HALF_OPEN race: only one wins.
     */
    @Test
    void concurrentOpenToHalfOpenRaceOnlyOneProbeWins() throws Exception {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Trip to OPEN
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Multiple threads race for probe at timeout
        int nThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger probeWinners = new AtomicInteger(0);

        for (int i = 0; i < nThreads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    if (EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)) {
                        probeWinners.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Exactly one thread should win the probe
        assertThat(probeWinners.get()).isEqualTo(1);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);
    }

    /**
     * Failure count threshold: transitions only when count >= minCalls.
     */
    @Test
    void tripOnlyAfterMinCallsReached() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Only 4 failures (< minCalls=5) must NOT trip
        for (int i = 1; i <= 4; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);

        // 5th failure should trip
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        EwmaCircuitBreaker.release(st, 5 * TAU, false, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    /**
     * Error threshold: consecutive failures (without interleaving successes) drive EWMA
     * to trip the breaker.
     *
     * <p>NOTE: With time-decay EWMA (τ=1000ms), interleaving successes significantly reduces
     * the error rate. This test uses consecutive failures to verify the breaker trips when
     * the EWMA error rate exceeds the threshold.</p>
     */
    @Test
    void consecutiveFailuresDriveEwmaToThreshold() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = new ResourceConfig(0x01, 0, 0, 500_000, 10, OPEN, TAU, 0, 1);

        // 10 consecutive failures, each spaced τ (1000ms) apart.
        // With τ spacing, α ≈ 1 - exp(-1) ≈ 0.632 per sample.
        // EWMA approaches ~63% after 10 consecutive failures (well above 50% threshold).
        for (int i = 1; i <= 10; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Should trip after 10 samples (EWMA error rate > 500_000 ppm)
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    /**
     * Errors spaced far apart (>> τ) decay fully, preventing false trips.
     * Time-decay EWMA correctly resets error rate.
     */
    @Test
    void errorsDecayOverTimePreventFalseTrip() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // 3 failures spaced τ apart (EWMA climbs)
        for (int i = 1; i <= 3; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }

        // Long idle (100τ), error rate should decay near 0
        long idleTime = 100 * TAU;
        EwmaCircuitBreaker.tryAcquire(st, cfg, idleTime);
        EwmaCircuitBreaker.release(st, idleTime + 1, true, cfg, true);

        // Even 2 more failures after idle shouldn't trip (EWMA cleared)
        for (int i = 0; i < 2; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, idleTime + 2 + i);
            EwmaCircuitBreaker.release(st, idleTime + 3 + i, false, cfg, true);
        }

        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }
}