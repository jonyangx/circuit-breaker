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
}
