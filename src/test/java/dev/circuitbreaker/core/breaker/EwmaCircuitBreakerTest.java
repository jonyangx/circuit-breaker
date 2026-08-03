package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EwmaCircuitBreaker 3-state machine + generation tests (UC-005; BR-020/024/025). TC-CAP-CB-001..005.
 * Failures are spaced ~τ apart so the time-decay EWMA climbs to the threshold within minCalls samples.
 */
class EwmaCircuitBreakerTest {

    private static final long TAU = 1000L;
    private static final long OPEN = 1000L;

    private static ResourceConfig breakerCfg() {
        // mask 0x01, 50% threshold, minCalls 5, open 1000ms, τ 1000ms
        return new ResourceConfig(0x01, 0, 0, 500_000, 5, OPEN, TAU, 0, 1);
    }

    /** Drive a fresh state to OPEN via 5 failures spaced τ apart. endTime ≈ 5τ + open = 6000. */
    private static ResourceState trip() {
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 0);
        for (int i = 1; i <= 5; i++) {
            EwmaCircuitBreaker.release(st, i * TAU, false, cfg, true); // now = 1000..5000
        }
        return st;
    }

    @Test
    void tripsOnSustainedHighErrorRate() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 5500)).isFalse(); // before endTime(~6000) → blocked
    }

    @Test
    void halfOpenSingleProbeThenRecover() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isTrue();  // past endTime → probe wins OPEN→HALF_OPEN
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isFalse(); // others blocked while probe in flight
        EwmaCircuitBreaker.release(st, 7000, true, cfg, true);          // probe success → CLOSED
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    @Test
    void generationPreventsImmediateReTripAfterRecovery() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 7000);               // → HALF_OPEN
        EwmaCircuitBreaker.release(st, 7000, true, cfg, true);  // → CLOSED (gen bumped → stale EWMA invalidated)
        // one failure right after recovery: count re-seeded to 1 < minCalls(5) → must NOT re-trip (BR-024)
        EwmaCircuitBreaker.release(st, 7001, false, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    @Test
    void probeFailureReopensBreaker() {
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        EwmaCircuitBreaker.tryAcquire(st, cfg, 7000);                // → HALF_OPEN
        EwmaCircuitBreaker.release(st, 7000, false, cfg, true); // probe fail → OPEN
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
    }

    @Test
    void lostProbeSelfHealsAfterGrace() {
        // A1: a lost HALF_OPEN probe (never released) must NOT strand the resource forever.
        ResourceState st = trip();
        ResourceConfig cfg = breakerCfg();
        // trip endTime ≈ 6000; past it → elect a probe (HALF_OPEN, deadline 7000 + open 1000 = 8000)
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7000)).isTrue();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.HALF_OPEN);
        // probe is never released...
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 7500)).isFalse(); // before deadline → still blocked
        // at/past the deadline → re-arm to OPEN (self-heal), this acquire blocked
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 9000)).isFalse();
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.OPEN);
        // past the re-armed OPEN window (9000 + 1000 = 10000) → fresh probe elected, recovers on success
        assertThat(EwmaCircuitBreaker.tryAcquire(st, cfg, 10500)).isTrue();
        EwmaCircuitBreaker.release(st, 10500, true, cfg, true);
        assertThat(EwmaCircuitBreaker.brState(st.breakerState.get())).isEqualTo(EwmaCircuitBreaker.CLOSED);
    }

    @Test
    void generationIs8BitsAndAlignsAcrossStateLongs() {
        // C3: generation widened 4→8 bits in BOTH breakerState and ewmaState (they align by value).
        // 6 full cycles CLOSED→OPEN→HALF_OPEN→CLOSED = 18 transitions ⇒ gen=18. A 4-bit field would
        // wrap to 18 & 0xF = 2; an 8-bit field holds 18.
        ResourceState st = new ResourceState();
        ResourceConfig cfg = breakerCfg();
        for (int cycle = 0; cycle < 6; cycle++) {
            long t = 100_000L + cycle * 10_000L;
            assertThat(EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.CLOSED, EwmaCircuitBreaker.OPEN, t)).isTrue();
            assertThat(EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.OPEN, EwmaCircuitBreaker.HALF_OPEN, t)).isTrue();
            assertThat(EwmaCircuitBreaker.transition(st, EwmaCircuitBreaker.HALF_OPEN, EwmaCircuitBreaker.CLOSED, 0L)).isTrue();
        }
        assertThat(EwmaCircuitBreaker.brGen(st.breakerState.get())).isEqualTo(18);
        // ewmaState generation must align: a CLOSED release re-seeds ewmaState to the authoritative gen.
        EwmaCircuitBreaker.release(st, 200_001L, true, cfg, true);
        assertThat(EwmaCircuitBreaker.ewGen(st.ewmaState.get())).isEqualTo(18);
    }

    /**
     * Verify that a long idle (past the old 20-bit 17.5min range) followed by a SUCCESS
     * fully clears a stale error rate, preventing it from causing a false trip.
     * With 16ms quantization the wrap range is now 2^24 ms (4.66h); the test uses
     * 2^20 + 2000ms which would have been a dangerous alias in the old 20-bit field
     * (dt aliased to 0 → α=0, stale ppm frozen). New code: dt ≈ 2^20 ms → u >> 8 → α=1.
     */
    @Test
    void idlePast20BitWrapDoesNotFreezeStalePpm() {
        ResourceConfig c = breakerCfg();
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);
        // Drive ppm near threshold with 2 failures at τ intervals
        EwmaCircuitBreaker.release(st, 1000L, false, c, true);
        EwmaCircuitBreaker.release(st, 2000L, false, c, true);
        int ppmBeforeIdle = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        assertThat(ppmBeforeIdle).isGreaterThan(500_000); // near threshold
        // Idle past the old 20-bit field range. With quantization, nowQ = (2^20+2000)>>4 ≈ 65597,
        // ewLast(prime) = 125 → dtQ ≈ 65472 → dtMs ≈ 2^20 ms → α=1 (full decay).
        // OLD (20-bit ms field) would compute dt = (2^20+2000 - 2000) & 0xFFFFF = 0 → α=0, freezing stale ppm.
        long idlePastWrap = (1L << 20) + 2000;
        EwmaCircuitBreaker.release(st, idlePastWrap, true, c, true);
        // The success must fully clear the stale ppm to 0, preventing a false trip.
        assertThat(EwmaCircuitBreaker.ewPpm(st.ewmaState.get())).isZero();
    }

    /**
     * Verify that two samples within the same 16ms quantum still have α=0 (no decay),
     * preserving the micro-burst low-pass damping behavior. With 16ms quantization,
     * samples at the same wall-clock ms (or within <16ms) map to the same quantized tick.
     */
    @Test
    void sameMsAlphaRemainsZeroWithQuantization() {
        ResourceConfig c = breakerCfg();
        ResourceState st = new ResourceState();
        EwmaCircuitBreaker.tryAcquire(st, c, 0);
        // First failure at 1000ms: dt from initial lastUpdateMs=0 → real α ≈ 0.63 → ppm jumps
        EwmaCircuitBreaker.release(st, 1000L, false, c, true);
        int ppmAfterFirst = EwmaCircuitBreaker.ewPpm(st.ewmaState.get());
        assertThat(ppmAfterFirst).isGreaterThan(500_000); // real α applied
        // Second failure at 1007ms (same 16ms quantum as 1000ms: both map to nowQ=62)
        // → dtQ=0 → α=0 → ppm unchanged (micro-burst damping preserved)
        EwmaCircuitBreaker.release(st, 1007L, false, c, true);
        assertThat(EwmaCircuitBreaker.ewPpm(st.ewmaState.get())).isEqualTo(ppmAfterFirst);
        // Third failure at 1007ms again → still dtQ=0 → α=0 → unchanged
        EwmaCircuitBreaker.release(st, 1007L, false, c, true);
        assertThat(EwmaCircuitBreaker.ewPpm(st.ewmaState.get())).isEqualTo(ppmAfterFirst);
    }
}