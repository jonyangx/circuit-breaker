package dev.circuitbreaker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline policy sanity-checker: validates that a {@link ResourceConfig} derived from an
 * upstream SLA respects the cross-parameter invariants the online algorithms assume.
 *
 * <p>{@link PolicyBuilder} validates each field in isolation (qps&gt;0, errThreshold∈(0,1], …);
 * this class validates <b>relationships</b> between fields and the SLA the config was built from.
 * Run it at registration or hot-reload time — never on the request hot path.</p>
 *
 * <p>Invariants (see docs/brd/design.md §4 and the SLA→param guide):</p>
 * <ul>
 *   <li><b>S1 headroom</b>: {@code qps < slaTps} — leave margin so the caller stays below the
 *       downstream's hard ceiling.</li>
 *   <li><b>S2 Little's law</b>: {@code concurrencyLimit >= qps × p99RT(s)} — else healthy slow
 *       requests are shed by the concurrency gate.</li>
 *   <li><b>S3 sample accumulation</b>: {@code minCalls / TPS(s) << ewmaTauMs/1000} — samples must
 *       accumulate well within the EWMA memory window, or the breaker never trips / trips stale.</li>
 *   <li><b>S4 trip margin</b>: {@code errThresholdPpm >= 10 × steadyErrorRatePpm} — trip well above
 *       the steady-state error rate, or normal jitter trips the breaker.</li>
 *   <li><b>S5 cold-start floor</b>: {@code minCalls >= 10} (WARN) / {@code >= 3} (ERROR) — too small a
 *       floor lets the breaker trip on one or two early failures (false cold-start trip).</li>
 * </ul>
 *
 * <p>Each gate is evaluated only if its capability bit is set in {@code cfg.mask}; S3 uses
 * {@code cfg.qps} as the TPS upper bound when rate-limit is enabled, otherwise falls back to
 * {@code slaTps} as an optimistic bound.</p>
 *
 * UC-001 (registration) / UC-008 (hot-reload).
 */
public final class PolicySpec {

    /** Fraction of the EWMA memory window within which minCalls must accumulate (S3). */
    private static final double SAMPLE_WINDOW_FRACTION = 0.10;
    /** How many × above steady-state error rate the trip threshold should sit (S4). */
    private static final int TRIP_MARGIN_X = 10;
    /** Below this fraction of slaTps, headroom is considered thin (S1 warning). */
    private static final double HEADROOM_WARN_FRACTION = 0.95;
    /** S5: minCalls cold-start floor — below this is a warning (thin sample floor). */
    private static final int MIN_CALLS_WARN = 10;
    /** S5: minCalls cold-start floor — below this is an error (can trip on 1-2 early failures). */
    private static final int MIN_CALLS_ERROR = 3;

    /** Observed/contracted facts about the downstream service, supplied by the operator. */
    public static final class SlaFacts {
        public final long slaTps;            // downstream TPS ceiling from the SLA
        public final long avgRtMs;           // average response time (ms)
        public final long p99RtMs;           // p99 response time (ms)
        public final int steadyErrorRatePpm; // normal/steady-state error rate (ppm)

        public SlaFacts(long slaTps, long avgRtMs, long p99RtMs, int steadyErrorRatePpm) {
            this.slaTps = slaTps;
            this.avgRtMs = avgRtMs;
            this.p99RtMs = p99RtMs;
            this.steadyErrorRatePpm = steadyErrorRatePpm;
        }
    }

    public enum Level { OK, WARN, ERROR }

    public static final class Finding {
        public final Level level;
        public final String rule;   // S1..S4
        public final String message;

        public Finding(Level level, String rule, String message) {
            this.level = level;
            this.rule = rule;
            this.message = message;
        }

        @Override public String toString() { return level + " [" + rule + "] " + message; }
    }

    private PolicySpec() {}

    /**
     * Check a config against SLA facts. Only gates enabled in {@code cfg.mask} are evaluated.
     *
     * @return unmodifiable list of findings (OK findings included for transparency).
     */
    public static List<Finding> check(ResourceConfig cfg, SlaFacts sla) {
        List<Finding> out = new ArrayList<>();
        boolean rl = (cfg.mask & ResourceConfig.MASK_RATE_LIMIT) != 0;
        boolean cb = (cfg.mask & ResourceConfig.MASK_CIRCUIT_BREAKER) != 0;
        boolean cc = (cfg.mask & ResourceConfig.MASK_CONCURRENCY) != 0;

        if (rl) checkHeadroom(cfg, sla, out);
        if (cc) checkConcurrency(cfg, sla, out);
        if (cb) {
            checkSampleAccumulation(cfg, sla, out);
            checkTripMargin(cfg, sla, out);
            checkMinCallsFloor(cfg, out);
        }
        return Collections.unmodifiableList(out);
    }

    /** @return true if no ERROR-level finding (WARN is tolerated, just surfaced). */
    public static boolean isValid(ResourceConfig cfg, SlaFacts sla) {
        for (Finding f : check(cfg, sla)) {
            if (f.level == Level.ERROR) return false;
        }
        return true;
    }

    // ---- S1: rate-limit headroom below the SLA ceiling ----
    private static void checkHeadroom(ResourceConfig cfg, SlaFacts sla, List<Finding> out) {
        if (cfg.qps >= sla.slaTps) {
            out.add(new Finding(Level.ERROR, "S1",
                "qps=" + cfg.qps + " >= slaTps=" + sla.slaTps
                    + "; leave headroom or the caller hits the downstream hard limit"));
        } else if (cfg.qps > Math.ceil(sla.slaTps * HEADROOM_WARN_FRACTION)) {
            out.add(new Finding(Level.WARN, "S1",
                "qps=" + cfg.qps + " leaves <5% headroom below slaTps=" + sla.slaTps));
        } else {
            out.add(new Finding(Level.OK, "S1",
                "qps=" + cfg.qps + " has headroom below slaTps=" + sla.slaTps));
        }
    }

    // ---- S2: concurrency vs Little's law (qps × RT) ----
    private static void checkConcurrency(ResourceConfig cfg, SlaFacts sla, List<Finding> out) {
        long needAvg = Math.ceilDiv(cfg.qps * sla.avgRtMs, 1_000L);
        long needP99 = Math.ceilDiv(cfg.qps * sla.p99RtMs, 1_000L);
        if (cfg.concurrencyLimit < needAvg) {
            out.add(new Finding(Level.ERROR, "S2",
                "concurrencyLimit=" + cfg.concurrencyLimit + " < qps×avgRT=" + needAvg
                    + "; even average traffic is shed"));
        } else if (cfg.concurrencyLimit < needP99) {
            out.add(new Finding(Level.WARN, "S2",
                "concurrencyLimit=" + cfg.concurrencyLimit + " < qps×p99RT=" + needP99
                    + "; slow (p99) requests get shed — raise the limit or accept latency shedding"));
        } else {
            out.add(new Finding(Level.OK, "S2",
                "concurrencyLimit=" + cfg.concurrencyLimit + " >= qps×p99RT=" + needP99));
        }
    }

    // ---- S3: samples accumulate within the EWMA memory window ----
    private static void checkSampleAccumulation(ResourceConfig cfg, SlaFacts sla, List<Finding> out) {
        boolean rl = (cfg.mask & ResourceConfig.MASK_RATE_LIMIT) != 0;
        long tpsBound = rl ? cfg.qps : sla.slaTps; // own limiter is the real cap; else downstream SLA
        String src = rl ? ("qps=" + cfg.qps) : ("slaTps=" + sla.slaTps + " (no rate-limit; optimistic)");
        double sampleWindowSec = (double) cfg.minCalls / (double) Math.max(1L, tpsBound);
        double memoryWindowSec = cfg.ewmaTauMs / 1_000.0;
        if (sampleWindowSec >= memoryWindowSec) {
            out.add(new Finding(Level.ERROR, "S3",
                "minCalls=" + cfg.minCalls + " take " + sampleWindowSec + "s to accumulate at "
                    + src + " >= τ=" + memoryWindowSec + "s; breaker can never reach minCalls"
                    + " within one memory window"));
        } else if (sampleWindowSec > memoryWindowSec * SAMPLE_WINDOW_FRACTION) {
            out.add(new Finding(Level.WARN, "S3",
                "minCalls=" + cfg.minCalls + " take " + sampleWindowSec + "s vs τ="
                    + memoryWindowSec + "s (" + src + "); accumulates too close to the window edge"));
        } else {
            out.add(new Finding(Level.OK, "S3",
                "minCalls=" + cfg.minCalls + " accumulate in " + sampleWindowSec
                    + "s, well within τ=" + memoryWindowSec + "s (" + src + ")"));
        }
    }

    // ---- S4: trip threshold above steady-state error rate ----
    private static void checkTripMargin(ResourceConfig cfg, SlaFacts sla, List<Finding> out) {
        if (cfg.errThresholdPpm <= sla.steadyErrorRatePpm) {
            out.add(new Finding(Level.ERROR, "S4",
                "errThreshold=" + cfg.errThresholdPpm + "ppm <= steadyErrorRate="
                    + sla.steadyErrorRatePpm + "ppm; the breaker trips on NORMAL traffic"));
        } else if (cfg.errThresholdPpm < (long) TRIP_MARGIN_X * sla.steadyErrorRatePpm) {
            out.add(new Finding(Level.WARN, "S4",
                "errThreshold=" + cfg.errThresholdPpm + "ppm is <" + TRIP_MARGIN_X
                    + "× steadyErrorRate=" + sla.steadyErrorRatePpm + "ppm; normal jitter may trip"));
        } else {
            out.add(new Finding(Level.OK, "S4",
                "errThreshold=" + cfg.errThresholdPpm + "ppm >> steadyErrorRate="
                    + sla.steadyErrorRatePpm + "ppm"));
        }
    }

    // ---- S5: minCalls cold-start floor (avoid false trip on 1-2 early failures) ----
    private static void checkMinCallsFloor(ResourceConfig cfg, List<Finding> out) {
        if (cfg.minCalls < MIN_CALLS_ERROR) {
            out.add(new Finding(Level.ERROR, "S5",
                "minCalls=" + cfg.minCalls + " < " + MIN_CALLS_ERROR
                    + "; the breaker can trip on a single early failure (cold-start false trip)"));
        } else if (cfg.minCalls < MIN_CALLS_WARN) {
            out.add(new Finding(Level.WARN, "S5",
                "minCalls=" + cfg.minCalls + " < " + MIN_CALLS_WARN
                    + "; very thin cold-start sample floor — prone to jitter trips"));
        } else {
            out.add(new Finding(Level.OK, "S5",
                "minCalls=" + cfg.minCalls + " >= " + MIN_CALLS_WARN + " cold-start floor"));
        }
    }
}
