package dev.circuitbreaker.core.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * SystemOverload shed validation test (AA Report §3.1 HIGH).
 * Verifies setShedPermilleForTest range validation and graded shedding behavior.
 *
 * <p>Note: the test JVM is launched with -Dcircuitbreaker.testMode=true (see build.gradle.kts),
 * so TEST_MODE is true and setShedPermilleForTest is callable.</p>
 */
class SystemOverloadShedValidationTest {

    // Defect 8 residual: inline try/finally resets only cover the passing path — a failed
    // assertion mid-test would leak SHED_PERMILLE into other classes. Same @AfterEach discipline
    // as SystemOverloadTest: always restore the global shed state after every test.
    @AfterEach
    void reset() {
        SystemOverload.stopProbe();
        SystemOverload.setShedPermilleForTest(0);
    }

    /**
     * AA §3.1: setShedPermilleForTest must reject values outside [0, 1000].
     * This is the DA fix: range validation added even in test mode.
     */
    @Test
    void setShedPermilleForTestRejectsNegative() {
        assertThatThrownBy(() -> SystemOverload.setShedPermilleForTest(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permille");

        assertThatThrownBy(() -> SystemOverload.setShedPermilleForTest(-100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * AA §3.1: values > 1000 (100%) must be rejected.
     */
    @Test
    void setShedPermilleForTestRejectsAboveThousand() {
        assertThatThrownBy(() -> SystemOverload.setShedPermilleForTest(1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permille");

        // The adversarial value from AA report (10000)
        assertThatThrownBy(() -> SystemOverload.setShedPermilleForTest(10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * AA §3.1: valid boundary values [0, 1000] are accepted.
     */
    @Test
    void setShedPermilleForTestAcceptsValidBoundaries() {
        SystemOverload.setShedPermilleForTest(0);
        assertThat(SystemOverload.SHED_PERMILLE).isZero();

        SystemOverload.setShedPermilleForTest(500);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(500);

        SystemOverload.setShedPermilleForTest(1000);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(1000);

        // Reset to safe value
        SystemOverload.setShedPermilleForTest(0);
    }

    /**
     * maybeShed at permille=0 never sheds.
     */
    @Test
    void maybeShedNeverShedsAtZero() {
        SystemOverload.setShedPermilleForTest(0);
        try {
            for (int i = 0; i < 1000; i++) {
                assertThat(SystemOverload.maybeShed()).isFalse();
            }
        } finally {
            SystemOverload.setShedPermilleForTest(0);
        }
    }

    /**
     * maybeShed at permille=1000 always sheds.
     */
    @Test
    void maybeShedAlwaysShedsAtThousand() {
        SystemOverload.setShedPermilleForTest(1000);
        try {
            for (int i = 0; i < 1000; i++) {
                assertThat(SystemOverload.maybeShed()).isTrue();
            }
        } finally {
            SystemOverload.setShedPermilleForTest(0);
        }
    }

    /**
     * Graded shedding: onCpuSample maps CPU% to shed permille levels.
     * BR-041 graded levels: <60→0, 60→200, 80→500, 90→800.
     */
    @Test
    void onCpuSampleGradedLevels() {
        // Low CPU → no shedding
        invokeOnCpuSample(50.0);
        assertThat(SystemOverload.SHED_PERMILLE).isZero();

        // 60% CPU → 200 permille
        invokeOnCpuSample(60.0);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(200);

        // 80% CPU → 500 permille
        invokeOnCpuSample(80.0);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(500);

        // 90% CPU → 800 permille
        invokeOnCpuSample(90.0);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(800);

        // Reset
        invokeOnCpuSample(10.0);
        // Hysteresis: 800 → exit threshold 80, 10 < 80 → falls
    }

    /**
     * Hysteresis: level rises immediately but falls only past exit threshold (BR-041).
     * When falling, the level drops to the target corresponding to the current CPU bucket
     * (not necessarily the next-higher bucket).
     */
    @Test
    void hysteresisFallsOnlyPastExitThreshold() {
        invokeOnCpuSample(95.0); // → 800
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(800);

        // CPU drops to 85 (below 90 enter but above 80 exit for level 800)
        invokeOnCpuSample(85.0);
        // Still 800 (hysteresis: exit threshold for 800 is 80, 85 > 80 → no fall)
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(800);

        // CPU drops to 79 (below exit threshold 80 for level 800).
        // 79% falls in the 60–79 bucket → target=200, so level falls to 200.
        invokeOnCpuSample(79.0);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(200);

        // Reset to safe
        invokeOnCpuSample(10.0);
    }

    /** Reflective helper to invoke package-private onCpuSample. */
    private static void invokeOnCpuSample(double cpuPercent) {
        try {
            var m = SystemOverload.class.getDeclaredMethod("onCpuSample", double.class);
            m.setAccessible(true);
            m.invoke(null, cpuPercent);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}