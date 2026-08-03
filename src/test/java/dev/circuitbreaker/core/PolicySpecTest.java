package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static dev.circuitbreaker.core.PolicySpec.Level.ERROR;
import static dev.circuitbreaker.core.PolicySpec.Level.OK;
import static dev.circuitbreaker.core.PolicySpec.Level.WARN;

/**
 * PolicySpec cross-parameter validation (SLA→param invariants S1–S4).
 * SLA used throughout: 2000 TPS, avg 50ms, p99 100ms, 99.9% available (1000ppm steady).
 */
class PolicySpecTest {

    private static final int ALL = ResourceConfig.MASK_CIRCUIT_BREAKER
                                 | ResourceConfig.MASK_RATE_LIMIT
                                 | ResourceConfig.MASK_CONCURRENCY;

    /** Direct ResourceConfig ctor (bypasses PolicyBuilder so we can probe invalid combinations). */
    private static ResourceConfig cfg(int mask, long qps, int errPpm, int minCalls,
                                      long tauMs, int concurrency) {
        return new ResourceConfig(mask, qps, qps, errPpm, minCalls, 10_000L, tauMs, concurrency, 1);
    }

    private static final PolicySpec.SlaFacts ORDER_SLA =
            new PolicySpec.SlaFacts(2000, 50, 100, 1000);

    private static PolicySpec.Level levelOf(List<PolicySpec.Finding> fs, String rule) {
        return fs.stream()
                .filter(f -> f.rule.equals(rule))
                .map(f -> f.level)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding for rule " + rule));
    }

    @Test
    void orderApiExampleFromGuideFlagsP99Shedding() {
        // Exact config derived in the SLA→param guide (concurrency=80).
        ResourceConfig c = cfg(ALL, 1600, 100_000, 50, 5_000, 80);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(levelOf(fs, "S1")).isEqualTo(OK);
        assertThat(levelOf(fs, "S2")).isEqualTo(WARN); // 80 < qps×p99RT=160 — caught!
        assertThat(levelOf(fs, "S3")).isEqualTo(OK);
        assertThat(levelOf(fs, "S4")).isEqualTo(OK);
        assertThat(PolicySpec.isValid(c, ORDER_SLA)).isTrue(); // WARN tolerated
    }

    @Test
    void orderApiExampleFixedPassesClean() {
        // Use qps=1000 (multiple of 1000 → S6 OK) with concurrency 160 ≥ qps×p99RT=100 → all OK.
        ResourceConfig c = cfg(ALL, 1000, 100_000, 50, 5_000, 160);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(fs).allMatch(f -> f.level == OK);
    }

    @Test
    void s1NoHeadroomIsError() {
        ResourceConfig c = cfg(ALL, 2000, 100_000, 50, 5_000, 160); // qps == slaTps
        assertThat(levelOf(PolicySpec.check(c, ORDER_SLA), "S1")).isEqualTo(ERROR);
        assertThat(PolicySpec.isValid(c, ORDER_SLA)).isFalse();
    }

    @Test
    void s2BelowAvgIsError() {
        ResourceConfig c = cfg(ALL, 1600, 100_000, 50, 5_000, 40); // < qps×avgRT=80
        assertThat(levelOf(PolicySpec.check(c, ORDER_SLA), "S2")).isEqualTo(ERROR);
    }

    @Test
    void s3SamplesCannotAccumulateIsError() {
        // minCalls=20000 → 12.5s to accumulate at qps=1600, > τ=5s.
        ResourceConfig c = cfg(ALL, 1600, 100_000, 20_000, 5_000, 160);
        assertThat(levelOf(PolicySpec.check(c, ORDER_SLA), "S3")).isEqualTo(ERROR);
    }

    @Test
    void s4ThresholdBelowSteadyIsError() {
        ResourceConfig c = cfg(ALL, 1600, 500, 50, 5_000, 160); // 500ppm < steady 1000ppm
        assertThat(levelOf(PolicySpec.check(c, ORDER_SLA), "S4")).isEqualTo(ERROR);
    }

    @Test
    void disabledGatesAreSkipped() {
        // Rate-limit only: S2/S3/S4 must be absent. S6 fires for qps=1600 (not multiple of 1000).
        ResourceConfig c = cfg(ResourceConfig.MASK_RATE_LIMIT, 1600, 0, 1, 5_000, 0);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(fs).extracting(f -> f.rule).containsExactlyInAnyOrder("S1", "S6");
    }

    @Test
    void breakerOnlyUsesSlaTpsAsTpsBound() {
        // No rate-limit → S3 falls back to slaTps as the optimistic TPS bound. S5 also runs (cb enabled).
        ResourceConfig c = cfg(ResourceConfig.MASK_CIRCUIT_BREAKER, 0, 100_000, 50, 5_000, 0);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(fs).extracting(f -> f.rule).containsExactlyInAnyOrder("S3", "S4", "S5");
        PolicySpec.Finding s3 = fs.stream().filter(f -> f.rule.equals("S3")).findFirst().orElseThrow();
        assertThat(s3.message).contains("slaTps=2000");
    }

    @Test
    void s5MinCallsFloorIsErrorBelow3AndWarnBelow10() {
        // minCalls=2 → S5 ERROR (cold-start false-trip risk)
        ResourceConfig low = cfg(ALL, 1600, 100_000, 2, 5_000, 160);
        assertThat(levelOf(PolicySpec.check(low, ORDER_SLA), "S5")).isEqualTo(ERROR);
        assertThat(PolicySpec.isValid(low, ORDER_SLA)).isFalse();
        // minCalls=5 → S5 WARN (thin floor)
        ResourceConfig thin = cfg(ALL, 1600, 100_000, 5, 5_000, 160);
        assertThat(levelOf(PolicySpec.check(thin, ORDER_SLA), "S5")).isEqualTo(WARN);
        assertThat(PolicySpec.isValid(thin, ORDER_SLA)).isTrue(); // WARN tolerated
        // minCalls=50 → S5 OK
        ResourceConfig ok = cfg(ALL, 1600, 100_000, 50, 5_000, 160);
        assertThat(levelOf(PolicySpec.check(ok, ORDER_SLA), "S5")).isEqualTo(OK);
    }

    @Test
    void s6RateLimitFloorWarnsOnNonMultiplesOf1000() {
        // qps=1500 → effective rate ≈ 1000/s (worst-case at ~1ms cadence) → S6 WARN
        ResourceConfig c = cfg(ResourceConfig.MASK_RATE_LIMIT, 1500, 0, 1, 5_000, 0);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(levelOf(fs, "S6")).isEqualTo(WARN);
        assertThat(PolicySpec.isValid(c, ORDER_SLA)).isTrue(); // WARN tolerated
    }

    @Test
    void s6RateLimitFloorOkOnMultiplesOf1000() {
        // qps=1000 → exact rate delivery → S6 OK
        ResourceConfig c = cfg(ResourceConfig.MASK_RATE_LIMIT, 1000, 0, 1, 5_000, 0);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(levelOf(fs, "S6")).isEqualTo(OK);
    }

    @Test
    void s7ConcurrencyOvershootWarnsBelow100() {
        // concurrencyLimit=50 → actual max ≈ 66 (50+16, 32% over) → S7 WARN
        ResourceConfig c = cfg(ResourceConfig.MASK_CONCURRENCY, 0, 0, 1, 5_000, 50);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(levelOf(fs, "S7")).isEqualTo(WARN);
        assertThat(PolicySpec.isValid(c, ORDER_SLA)).isTrue(); // WARN tolerated
    }

    @Test
    void s7ConcurrencyOvershootOkAbove100() {
        // concurrencyLimit=150 → actual max ≈ 166 (150+16, 10.7% over) → S7 OK
        ResourceConfig c = cfg(ResourceConfig.MASK_CONCURRENCY, 0, 0, 1, 5_000, 150);
        List<PolicySpec.Finding> fs = PolicySpec.check(c, ORDER_SLA);
        assertThat(levelOf(fs, "S7")).isEqualTo(OK);
    }
}
