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

    /** Test/config hook to force a shed level. */
    public static void setShedPermilleForTest(int permille) {
        SHED_PERMILLE = permille;
        currentLevel = permille;
    }

    /** Start the low-frequency CPU probe daemon (BR-042: off the request hot path). */
    public static void startProbe() {
        if (!probeRunning.compareAndSet(false, true)) {
            return;
        }
        stopProbe = false;
        Thread t = new Thread(SystemOverload::probeLoop, "circuit-breaker-cpu-probe");
        t.setDaemon(true);
        t.start();
    }

    public static void stopProbe() {
        stopProbe = true;
        probeRunning.set(false);
    }

    private static void probeLoop() {
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        while (!stopProbe) {
            try {
                if (os != null) {
                    double load = os.getCpuLoad(); // 0..1, or -1 if unavailable
                    if (load >= 0) {
                        onCpuSample(load * 100.0);
                    }
                }
                // NANOSECONDS.sleep avoids Date.now(); 1s cadence
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignore) {
                // probe must never crash the guarded JVM
            }
        }
    }
}
