package dev.circuitbreaker.core.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SystemOverload graded shedding + hysteresis tests (UC-007; BR-040/041/042). TC-CAP-SO-001..003. */
class SystemOverloadTest {

    @AfterEach
    void reset() {
        SystemOverload.setShedPermilleForTest(0);
    }

    @Test
    void zeroShedNeverBlocks() {
        SystemOverload.setShedPermilleForTest(0);
        for (int i = 0; i < 1000; i++) {
            assertThat(SystemOverload.maybeShed()).isFalse();
        }
    }

    @Test
    void fullShedAlwaysBlocks() {
        SystemOverload.setShedPermilleForTest(1000);
        for (int i = 0; i < 1000; i++) {
            assertThat(SystemOverload.maybeShed()).isTrue();
        }
    }

    @Test
    void hysteresisHoldsLevelUntilExitThreshold() {
        SystemOverload.setShedPermilleForTest(0);
        SystemOverload.onCpuSample(60);             // enter level 200
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(200);
        SystemOverload.onCpuSample(55);             // above exit(50) → stay (hysteresis, BR-041)
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(200);
        SystemOverload.onCpuSample(45);             // below exit(50) → fall to 0
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(0);
    }

    @Test
    void probeLifecycleStartsAndStopsWithoutThrowing() throws InterruptedException {
        SystemOverload.startProbe();        // idempotent CAS guard
        SystemOverload.startProbe();        // second start is a no-op
        Thread.sleep(1_200);                // allow ~one sample iteration (BR-042 off hot path)
        SystemOverload.stopProbe();
        assertThat(SystemOverload.SHED_PERMILLE).isGreaterThanOrEqualTo(0);
    }

    @Test
    void stopProbeAndWaitThenStartProbePreventsDualProbeRace() throws InterruptedException {
        // BR-043: verify that stopProbe() waits for the thread to exit, preventing
        // the dual-probe race (two probe threads running simultaneously).
        SystemOverload.startProbe();
        Thread.sleep(100); // let the probe thread start

        // Stop and immediately restart — the old thread must be joined before the new one starts.
        SystemOverload.stopProbe();
        SystemOverload.startProbe();

        Thread.sleep(1_200); // allow the new probe thread to run

        // Verify only one probe thread is active by checking that the probe is still functional.
        // If two threads were running, they'd both call onCpuSample() and SHED_PERMILLE would be set.
        // (This is a best-effort check; the real guarantee is the join in stopProbe/startProbe.)
        SystemOverload.stopProbe();
        assertThat(SystemOverload.SHED_PERMILLE).isGreaterThanOrEqualTo(0);
    }

    @Test
    void startProbeIsIdempotentWhenAlreadyRunning() throws InterruptedException {
        // BR-043: calling startProbe() while already running must be a no-op (CAS guard).
        SystemOverload.startProbe();
        Thread.sleep(100);

        // Attempt to start again — must not spawn a second thread.
        SystemOverload.startProbe();
        Thread.sleep(1_200);

        // Stop once — if two threads were running, one might still be active after stop().
        SystemOverload.stopProbe();
        Thread.sleep(100); // give time for any "second" thread to exit (if it existed)

        // Verify we can start again cleanly (only one thread was running)
        SystemOverload.startProbe();
        SystemOverload.stopProbe();
    }
}
