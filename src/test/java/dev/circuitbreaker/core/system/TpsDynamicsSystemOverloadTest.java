package dev.circuitbreaker.core.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial TPS spike/jitter tests for system overload — validates that shedding is
 * DECOUPLED from TPS (it uses CPU as signal, not call rate) and that the probabilistic
 * drop rate matches the configured permille.
 *
 * Aligned with docs/system/07_ALGORITHM_DEEP_DIVE.md §9 (TPS dynamics).
 */
class TpsDynamicsSystemOverloadTest {

    @AfterEach
    void reset() {
        SystemOverload.setShedPermilleForTest(0);
    }

    /**
     * §9.4: TPS spike does NOT change SHED_PERMILLE.
     * SystemOverload only responds to CPU, never to call rate.
     * Driving many maybeShed() calls at SHED_PERMILLE=0 must never shed.
     */
    @Test
    void tpsSpikeAloneDoesNotTriggerShedding() {
        SystemOverload.setShedPermilleForTest(0);
        // Simulate a "TPS spike" — 100000 rapid maybeShed() calls
        int shedCount = 0;
        for (int i = 0; i < 100_000; i++) {
            if (SystemOverload.maybeShed()) shedCount++;
        }
        // SHED_PERMILLE=0 → never sheds regardless of call rate
        assertThat(shedCount).isZero();
    }

    /**
     * §9.4: Statistical drop rate matches configured permille under high TPS.
     * At SHED_PERMILLE=200 (20%), the fraction of shed calls should be ~0.20 (±tolerance).
     * This proves the probabilistic shedding is correctly distributed.
     */
    @Test
    void statisticalDropRateMatchesPermilleAtHighTps() {
        SystemOverload.setShedPermilleForTest(200); // 20%
        int total = 100_000;
        int shedCount = 0;
        for (int i = 0; i < total; i++) {
            if (SystemOverload.maybeShed()) shedCount++;
        }
        double rate = (double) shedCount / total;
        // With 100k samples, the std deviation of a Bernoulli(0.2) is ~0.00127.
        // ±1% is a very generous tolerance (≈8σ).
        assertThat(rate).isCloseTo(0.20, org.assertj.core.data.Offset.offset(0.01));
    }

    /**
     * §9.4: Jittered CPU samples at the boundary must not cause SHED_PERMILLE to oscillate.
     * Hysteresis keeps the level stable until the exit threshold is crossed.
     */
    @Test
    void cpuJitterAtBoundaryDoesNotOscillateShedLevel() {
        SystemOverload.setShedPermilleForTest(0);
        // Enter the 200‰ level (CPU ≥ 60)
        SystemOverload.onCpuSample(65);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(200);

        // Jitter around 60% — exit threshold for level 200 is 50.
        // Samples 55, 58, 62, 57, 59 — all above 50, must STAY at 200.
        for (double cpu : new double[]{55, 58, 62, 57, 59, 60, 56}) {
            SystemOverload.onCpuSample(cpu);
            assertThat(SystemOverload.SHED_PERMILLE)
                    .as("hysteresis holds level under boundary jitter at cpu=%.0f", cpu)
                    .isEqualTo(200);
        }

        // Only when CPU drops below the EXIT threshold (50) does the level fall
        SystemOverload.onCpuSample(49);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(0);
    }

    /**
     * §9.4: Rapid level escalation under a sustained CPU spike.
     * 60→80→90 must produce 200→500→800 in order.
     */
    @Test
    void sustainedCpuSpikeEscalatesGradedLevels() {
        SystemOverload.setShedPermilleForTest(0);
        SystemOverload.onCpuSample(60);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(200);
        SystemOverload.onCpuSample(80);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(500);
        SystemOverload.onCpuSample(90);
        assertThat(SystemOverload.SHED_PERMILLE).isEqualTo(800);
    }
}
