package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GovernanceException block-code mapping tests (UC-002; BR-004). */
class GovernanceExceptionTest {

    @Test
    void mapsEachBlockCodeToTypedException() {
        assertThatThrownBy(() -> GovernanceException.throwFor(BlockCode.RATE_LIMITER))
                .isInstanceOf(GovernanceException.RateLimitedException.class)
                .hasFieldOrPropertyWithValue("blockCode", BlockCode.RATE_LIMITER);
        assertThatThrownBy(() -> GovernanceException.throwFor(BlockCode.CIRCUIT_BREAKER))
                .isInstanceOf(GovernanceException.CircuitOpenException.class);
        assertThatThrownBy(() -> GovernanceException.throwFor(BlockCode.CONCURRENCY))
                .isInstanceOf(GovernanceException.ConcurrencyLimitedException.class);
        assertThatThrownBy(() -> GovernanceException.throwFor(BlockCode.SYSTEM_OVERLOAD))
                .isInstanceOf(GovernanceException.SystemOverloadedException.class);
    }

    @Test
    void allSubtypesShareCommonBaseAndCode() {
        GovernanceException.RateLimitedException e = new GovernanceException.RateLimitedException();
        assertThat(e).isInstanceOf(GovernanceException.class);
        assertThat(e.getBlockCode()).isEqualTo(BlockCode.RATE_LIMITER);
    }

    @Test
    void nonBlockCodeThrowsIllegalState() {
        assertThatThrownBy(() -> GovernanceException.throwFor(0L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void controlFlowExceptionSkipsStackTraceCapture() {
        // N4: fillInStackTrace is a no-op → no stack trace captured (cheap control-flow throw).
        GovernanceException e = GovernanceException.forToken(BlockCode.RATE_LIMITER);
        assertThat(e.getStackTrace()).isEmpty();
        assertThat(e.getBlockCode()).isEqualTo(BlockCode.RATE_LIMITER); // blockCode still set
    }
}
