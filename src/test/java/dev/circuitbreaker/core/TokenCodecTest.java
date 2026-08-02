package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenCodec tests (UC-002/003, BR-003/004). TC-API-002-001/005, TC-API-003-005.
 */
class TokenCodecTest {

    @Test
    void encodeDecodeRoundTrip() {
        long time = 123_456L;
        int ver = 17, bidx = 9, mask = 0x07;
        long token = TokenCodec.encode(time, ver, bidx, mask);
        assertThat(TokenCodec.decodeTime(token)).isEqualTo(time);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(ver);
        assertThat(TokenCodec.decodeBucket(token)).isEqualTo(bidx);
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(mask);
    }

    @Test
    void signBitAlwaysZero() {
        // max time field still must not set the sign bit → token >= 0 (BR-004).
        long token = TokenCodec.encode(TokenCodec.TIME_MASK, 0x3FF, 0xF, 0xFFF);
        assertThat(token).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void negativeBlockCodesKeptNegative() {
        assertThat(BlockCode.SYSTEM_OVERLOAD).isNegative();
        assertThat(BlockCode.CIRCUIT_BREAKER).isNegative();
        assertThat(BlockCode.RATE_LIMITER).isNegative();
        assertThat(BlockCode.CONCURRENCY).isNegative();
    }

    @Test
    void rtMsModularSubtraction() {
        long acquireTime = 1_000L;
        long token = TokenCodec.encode(acquireTime, 0, 0, 0);
        // RT = 250ms
        assertThat(TokenCodec.rtMs(1_250L, token)).isEqualTo(250L);
    }

    @Test
    void maskTruncatedTo12Bits() {
        long token = TokenCodec.encode(0, 0, 0, 0x1FFFF); // >12 bits
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(0xFFF);
    }

    @Test
    void bucketTruncatedTo4Bits() {
        long token = TokenCodec.encode(0, 0, 0xFF, 0); // >4 bits
        assertThat(TokenCodec.decodeBucket(token)).isEqualTo(0xF);
    }

    @Test
    void versionTruncatedTo10BitsAndWrapsAt1024() {
        // C2: version is 10 bits — wraps at 1024 hot-swaps (was 64 at 6 bits). BR-052 ABA window.
        assertThat(TokenCodec.VERSION_MASK).isEqualTo(0x3FFL);
        assertThat(TokenCodec.VERSION_BITS).isEqualTo(10);
        assertThat(TokenCodec.TIME_BITS).isEqualTo(37); // 4 bits borrowed from time (41→37)
        // 1025 masks down to 1 (1025 & 0x3FF == 1)
        long token = TokenCodec.encode(0, 1025, 0, 0);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(1);
    }
}
