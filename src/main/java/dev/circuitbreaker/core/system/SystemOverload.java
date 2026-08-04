package dev.circuitbreaker.core.system;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Graded probabilistic load shedding + hysteresis (UC-007).
 * BR-040 (graded SHED_PERMILLE), BR-041 (hysteresis), BR-042 (probe off hot path).
 *
 * A low-frequency daemon probe samples CPU every 1s and updates the volatile SHED_PERMILLE;
 * the hot path does a single volatile read + probabilistic drop before any resource policy.
 */
public final class SystemOverload {
    static volatile int SHED_PERMILLE = 0;
    private static volatile int currentLevel = 0;
    private static final double HYST_MARGIN = 10.0; // hysteresis: exit = enter - margin
    private static final AtomicBoolean probeRunning = new AtomicBoolean(false);
    private static volatile boolean stopProbe = false;
    private static volatile Thread probeThread = null; // BR-043: track thread to prevent dual-probe race
    private static final boolean TEST_MODE = Boolean.getBoolean("circuitbreaker.testMode");

    private SystemOverload() {}

    /** Hot-path check (single volatile read). */
    public static boolean maybeShed() {
        int shed = SHED_PERMILLE;
        return shed > 0 && ThreadLocalRandom.current().nextInt(1000) < shed;
    }

    /** Apply a CPU sample (percent 0..100) with graded levels + hysteresis. BR-041. */
    static void onCpuSample(double cpuPercent) {
        int target;
        if (cpuPercent >= 90) target = 800;
        else if (cpuPercent >= 80) target = 500;
        else if (cpuPercent >= 60) target = 200;
        else target = 0;

        int cur = currentLevel;
        if (target > cur) {
            currentLevel = target;                       // rise immediately at enter threshold
        } else if (target < cur && cpuPercent < exitThreshold(cur)) {
            currentLevel = target;                       // fall only past exit threshold (hysteresis)
        }
        SHED_PERMILLE = currentLevel;
    }

    private static int exitThreshold(int level) {
        return switch (level) {
            case 800 -> 80;
            case 500 -> 70;
            case 200 -> 50;
            default -> 0;
        };
    }

    /** Test-only hook to force a shed level; guarded against production misuse (BR-040). */
    public static void setShedPermilleForTest(int permille) {
        if (!TEST_MODE) {
            throw new IllegalStateException(
                "setShedPermilleForTest only allowed in test mode (-Dcircuitbreaker.testMode=true)");
        }
        // AA §3.1 fix: range validation even in test mode prevents extreme values from corrupting
        // graded shedding logic (e.g., permille=10000 would cause currentLevel to saturate while
        // maybeShed() stays at 1000% cap, breaking the exitThreshold() calculation).
        if (permille < 0 || permille > 1000) {
            throw new IllegalArgumentException(
                "permille must be in [0, 1000], got: " + permille);
        }
        SHED_PERMILLE = permille;
        currentLevel = permille;
    }

    /** Start the low-frequency CPU probe daemon (BR-042: off the request hot path). */
    public static void startProbe() {
        // First CAS: only the winning thread proceeds into the startup sequence.
        if (!probeRunning.compareAndSet(false, true)) {
            return; // another thread is already managing probe startup
        }
        try {
            stopProbe = false;
            Thread t = new Thread(() -> {
                try {
                    probeLoop();
                } finally {
                    probeRunning.set(false); // release slot on exit
                }
            }, "circuit-breaker-cpu-probe");
            t.setDaemon(true);

            // BR-043: ensure any previous probe thread has fully exited before we start.
            // Prevents dual-probe race if stopProbe()+startProbe() is called in quick succession.
            Thread prev = probeThread;
            probeThread = t;
            if (prev != null && prev.isAlive()) {
                try {
                    prev.join(2000); // wait up to 2s for the old thread to exit
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            // Final stopProbe re-check: if stopProbe() was called during startup,
            // abandon this probe and release the slot.
            if (stopProbe) {
                probeRunning.set(false);
                return;
            }

            t.start();
        } catch (RuntimeException e) {
            // Release slot on any exception so subsequent startProbe() can proceed.
            probeRunning.set(false);
            throw e;
        }
    }

    public static void stopProbe() {
        stopProbe = true;
        // BR-043: wait for the probe thread to fully exit before returning.
        // This prevents the dual-probe race where startProbe() is called immediately
        // after stopProbe() while the old thread is still in probeLoop().
        Thread t = probeThread;
        if (t != null) {
            try {
                t.join(2000); // wait up to 2s
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        // After join returns, the thread's finally block has set probeRunning=false,
        // allowing startProbe() to proceed safely.
    }

    private static void probeLoop() {
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        while (!stopProbe) {
            try {
                if (os != null) {
                    double load = os.getCpuLoad(); // 0..1, or -1 if unavailable
                    if (load >= 0 && load <= 1.0) { // range-validate before sampling
                        onCpuSample(load * 100.0);
                    }
                }
                // NANOSECONDS.sleep avoids Date.now(); 1s cadence
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (VirtualMachineError vme) {
                // Propagate VM-level errors (OOM, StackOverflow, etc.) to JVM default handler.
                throw vme;
            } catch (Throwable ignore) {
                // probe must never crash the guarded JVM
            }
        }
    }
}
