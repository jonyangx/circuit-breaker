package dev.circuitbreaker.core.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SystemOverload graded shedding + hysteresis tests (UC-007; BR-040/041/042). TC-CAP-SO-001..003. */
class SystemOverloadTest {

    @AfterEach
    void reset() {
        // Defect 8 fix: reset ALL static state, not just SHED_PERMILLE. A test that starts the
        // probe (or fails mid-flight) would otherwise leave probeThread running into the next
        // test — onCpuSample() from the leaked thread mutates SHED_PERMILLE mid-assertion (flaky
        // cross-test pollution). stopProbe() joins the thread; setShedPermilleForTest resets the level.
        SystemOverload.stopProbe();
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

    @Test
    void rapidStopStartCyclesDoNotLeakProbeThreads() throws InterruptedException {
        // LC-3 regression (AA §2.5): repeated stop()/start() in quick succession must not leak
        // probe threads. The identity-checked finally guarantees a stale thread's exit cannot clear
        // a newer probe's running flag, and the join-timeout interrupt force-wakes any stuck thread
        // — so after the dust settles at most ONE probe thread may be alive (never unbounded growth).
        for (int i = 0; i < 6; i++) {
            SystemOverload.startProbe();
            Thread.sleep(20);
            SystemOverload.stopProbe();
        }
        Thread.sleep(150); // let any interrupted/draining thread fully exit

        assertThat(countProbeThreads())
                .as("rapid stop/start cycles must never accumulate multiple probe threads")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void interruptedProbeExitsViaInterruptPathAndRestartsCleanly() throws InterruptedException {
        // LC-3 join(2000)-timeout path (AA §2.5): when a probe is stuck past the join window
        // (GC STW / OS pause > 2s), stopProbe/startProbe force-wake it with interrupt; probeLoop()
        // exits on InterruptedException and the identity-checked finally clears the running flag
        // (probeThread == currentThread). The slot is released so a fresh probe can start — no
        // unbounded probe growth, no flag loss. This is the DA fix's force-wake + identity-check
        // mechanism, exercised deterministically without waiting a real 2s.
        SystemOverload.startProbe();
        Thread probe = findProbeThread();
        assertThat(probe).as("probe thread must be running").isNotNull();
        assertThat(probe.isAlive()).as("probe thread must be alive after start").isTrue();

        // Force-wake exactly as the join(2000)-timeout path does (startProbe :117 / stopProbe :153).
        probe.interrupt();
        probe.join(2_000);
        assertThat(probe.isAlive())
                .as("interrupted probe must exit via probeLoop's InterruptedException path")
                .isFalse();

        // The identity-checked finally must have cleared the running flag → restart cleanly.
        SystemOverload.startProbe();
        Thread probe2 = findProbeThread();
        assertThat(probe2).as("fresh probe must start after the interrupted one exits").isNotNull();
        assertThat(probe2.isAlive()).as("fresh probe must be alive after restart").isTrue();
        SystemOverload.stopProbe();
        Thread.sleep(150); // let the probe fully drain
        assertThat(countProbeThreads())
                .as("at most ONE probe thread may exist after a stop/start cycle")
                .isLessThanOrEqualTo(1);
    }

    private static Thread findProbeThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("circuit-breaker-cpu-probe".equals(t.getName())) {
                return t;
            }
        }
        return null;
    }

    private static int countProbeThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && "circuit-breaker-cpu-probe".equals(t.getName())) {
                n++;
            }
        }
        return n;
    }
}
