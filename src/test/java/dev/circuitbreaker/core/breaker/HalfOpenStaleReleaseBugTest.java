package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Adversarial test: HALF_OPEN stale release bug verification (deterministic, virtual clock).
 *
 * Bug scenario:
 * 1. Create a breaker with openMillis=1000 and trip it (CLOSED→OPEN)
 * 2. Have in-flight requests from CLOSED (long RT) still pending when HALF_OPEN enters
 * 3. Have one of the stale in-flight releases during HALF_OPEN be successful
 * 4. Observe: does HALF_OPEN transition to CLOSED based on stale release?
 *
 * Key insight: release() reads breakerState once and dispatches purely on current state,
 * with NO validation that the release corresponds to the acquire that the HALF_OPEN probe
 * admitted. A request acquired during CLOSED that is released after the breaker has passed
 * through OPEN into HALF_OPEN will be treated as the probe's release.
 */
class HalfOpenStaleReleaseBugTest {

    private static final long TAU = 1000L;
    private static final long OPEN_MILLIS = 1000L;

    private static ResourceConfig breakerCfg() {
        return new ResourceConfig(0x01, 0, 0, 500_000, 5, OPEN_MILLIS, TAU, 0, 1);
    }

    /**
     * HALF_OPEN stale release bug: stale ok=true incorrectly closes breaker.
     *
     * Timeline (virtual clock, fully deterministic):
     * - t=0:        Acquire long-running request A in CLOSED (admitted)
     * - t=1000..5000: 5 OTHER requests fail → breaker trips OPEN (endTime=6000)
     * - t=7000:     Open timeout, elect probe → HALF_OPEN (probe never released)
     * - t=7500:     Stale release(A, ok=true) arrives from CLOSED era
     *
     * release() sees state=HALF_OPEN, ok=true → transition HALF_OPEN→CLOSED.
     * The probe itself was never released; A is not the probe.
     */
    @Test
    void halfOpenStaleReleaseShouldNotCloseBreaker() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // t=0: Acquire long-running request A (CLOSED era). A is NEVER released until t=7500.
        boolean acquireA = EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        assertThat(acquireA).isTrue();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);

        // t=1000..5000: 5 distinct other requests fail to trip the breaker (err ≥ 50%).
        // These have NOTHING to do with request A.
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, i * TAU);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }
        // Breaker now OPEN (endTime = 5000 + openMillis = 6000).
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);

        // t=5500: still blocked (before timeout).
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 5500)).isFalse();

        // t=7000: Open timeout, elect probe → HALF_OPEN.
        boolean probeAcquire = EwmaCircuitBreaker.tryAcquire(st, cfg, 7000);
        assertThat(probeAcquire).isTrue();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);
        int halfOpenGen = EwmaCircuitBreaker.brGen(st.breakerState.get());

        // The REAL probe (elected at t=7000) is NEVER released in this test.
        // At t=7500, the STALE release(A) arrives. A was admitted in CLOSED at t=0.
        EwmaCircuitBreaker.release(st, 7500, true, cfg, true);

        int stateAfterStaleRelease = EwmaCircuitBreaker.brState(st.breakerState.get());
        boolean bugConfirmed = (stateAfterStaleRelease == EwmaCircuitBreaker.CLOSED);

        printResult(
            "single-stale-ok-release",
            bugConfirmed,
            stateAfterStaleRelease,
            "Acquire long-RT request A at t=0 (CLOSED); 5 other failures trip CLOSED→OPEN (t=1000..5000, endTime=6000); probe elected at t=7000 (HALF_OPEN, probe never released); stale release(A, ok=true) arrives at t=7500.",
            halfOpenGen
        );
    }

    /**
     * Variant: stale release is a FAILURE (ok=false) → stale failure forces HALF_OPEN→OPEN.
     * This is the symmetric half of the bug: a stale failure re-arms OPEN even though the
     * real probe has not resolved yet, wasting the open window.
     */
    @Test
    void halfOpenStaleFailureReleaseReOpensBreaker() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();

        // Acquire long-RT request A in CLOSED.
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 0)).isTrue();

        // Trip to OPEN.
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.tryAcquire(st, cfg, i * TAU);
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true);
        }
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);

        // Elect probe → HALF_OPEN.
        EwmaCircuitBreaker.tryAcquire(st, cfg, 7000);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);

        // Stale release(A, ok=false) arrives.
        EwmaCircuitBreaker.release(st, 7500, false, cfg, true);

        int stateAfterStaleRelease = EwmaCircuitBreaker.brState(st.breakerState.get());
        // Bug (symmetric): stale failure transitions HALF_OPEN→OPEN prematurely.
        boolean bugConfirmed = (stateAfterStaleRelease == EwmaCircuitBreaker.OPEN);

        printResult(
            "single-stale-failure-release",
            bugConfirmed,
            stateAfterStaleRelease,
            "Same setup; stale release(A, ok=false) arrives at t=7500 during HALF_OPEN.",
            EwmaCircuitBreaker.brGen(st.breakerState.get())
        );
    }

    private static void printResult(String tag, boolean bugConfirmed, int finalState,
                                    String scenario, int genAfter) {
        String expected = "HALF_OPEN should ignore stale releases (release must belong to the "
                + "probe elected at OPEN→HALF_OPEN; a CLOSED-era release is unrelated) — "
                + "either remain HALF_OPEN until the real probe resolves, or self-heal to OPEN "
                + "on probe-deadline miss. Generation stored at acquire time could be checked "
                + "on release to reject stale releases.";
        String actual = bugConfirmed
                ? "release() read breakerState=HALF_OPEN and applied the ok flag directly: "
                + "stale release transitioned HALF_OPEN→"
                + (finalState == EwmaCircuitBreaker.CLOSED ? "CLOSED" : "OPEN")
                + " (gen=" + genAfter + "), even though the real probe was never released."
                : "Breaker correctly ignored the stale release; final state="
                + stateName(finalState) + " (gen=" + genAfter + ").";

        System.out.println("\n=== BUG VERIFICATION [" + tag + "] ===");
        System.out.println("confirmed      : " + bugConfirmed);
        System.out.println("final_state    : " + stateName(finalState)
                + " (CLOSED=0, OPEN=1, HALF_OPEN=2)");
        System.out.println("test_scenario  : " + scenario);
        System.out.println("expected_behavior: " + expected);
        System.out.println("actual_behavior  : " + actual);
        System.out.println("severity       : " + (bugConfirmed ? "CRITICAL" : "LOW"));
        System.out.println("==========================================\n");
    }

    private static String stateName(int s) {
        return switch (s) {
            case 0 -> "CLOSED";
            case 1 -> "OPEN";
            case 2 -> "HALF_OPEN";
            default -> "UNKNOWN(" + s + ")";
        };
    }
}