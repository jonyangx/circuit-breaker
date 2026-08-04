package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TokenCodec tests (UC-002/003, BR-003/004/053). TC-API-002-001/005, TC-API-003-005.
 */
class TokenCodecTest {

    @Test
    void encodeDecodeRoundTrip() {
        long time = 123_456L;
        int rid = 42, ver = 17, bidx = 9, mask = 0x07;
        long token = TokenCodec.encode(time, rid, ver, bidx, mask);
        assertThat(TokenCodec.decodeTime(token)).isEqualTo(time);
        assertThat(TokenCodec.decodeResourceId(token)).isEqualTo(rid);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(ver);
        assertThat(TokenCodec.decodeBucket(token)).isEqualTo(bidx);
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(mask);
    }

    @Test
    void signBitAlwaysZero() {
        // max time field still must not set the sign bit → token >= 0 (BR-004).
        // TIME_BITS=27 → max time is 2^27-1, shifted left 36 lands at bit62 (sign bit63 is 0).
        long token = TokenCodec.encode(TokenCodec.TIME_MASK, 0x3FF, 0x3FF, 0xF, 0xFFF);
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
        long token = TokenCodec.encode(acquireTime, 0, 0, 0, 0);
        // RT = 250ms
        assertThat(TokenCodec.rtMs(1_250L, token)).isEqualTo(250L);
    }

    @Test
    void maskTruncatedTo12Bits() {
        long token = TokenCodec.encode(0, 0, 0, 0, 0x1FFF); // >12 bits
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(0xFFF);
    }

    @Test
    void bucketTruncatedTo4Bits() {
        long token = TokenCodec.encode(0, 0, 0, 0xFF, 0); // >4 bits
        assertThat(TokenCodec.decodeBucket(token)).isEqualTo(0xF);
    }

    @Test
    void resourceIdTruncatedTo10Bits() {
        long token = TokenCodec.encode(0, 0x7FFF, 0, 0, 0); // >10 bits
        assertThat(TokenCodec.decodeResourceId(token)).isEqualTo(0x3FF);
    }

    @Test
    void versionTruncatedTo10BitsAndWrapsAt1024() {
        // C2: version is 10 bits — wraps at 1024 hot-swaps (was 64 at 6 bits). BR-052 ABA window.
        assertThat(TokenCodec.VERSION_MASK).isEqualTo(0x3FFL);
        assertThat(TokenCodec.VERSION_BITS).isEqualTo(10);
        assertThat(TokenCodec.TIME_BITS).isEqualTo(27); // reduced to fit 12-bit mask + 10-bit resourceId
        assertThat(TokenCodec.RESOURCE_BITS).isEqualTo(10); // covers MAX_RESOURCES=1024
        assertThat(TokenCodec.MASK_BITS).isEqualTo(12); // design §3.2: 12 bits for future extensibility
        // 1025 masks down to 1 (1025 & 0x3FF == 1)
        long token = TokenCodec.encode(0, 0, 1025, 0, 0);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(1);
    }

    @Test
    void resourceIdDecodesFromToken() {
        // Test that resourceId is correctly encoded and decoded across the 10-bit field.
        // Cover the range: 0, 1, 511, 512, 1023, 1024 (wraps to 0)
        long t0 = TokenCodec.encode(0, 0, 0, 0, 0);
        assertThat(TokenCodec.decodeResourceId(t0)).isEqualTo(0);

        long t1 = TokenCodec.encode(0, 1, 0, 0, 0);
        assertThat(TokenCodec.decodeResourceId(t1)).isEqualTo(1);

        long t511 = TokenCodec.encode(0, 511, 0, 0, 0);
        assertThat(TokenCodec.decodeResourceId(t511)).isEqualTo(511);

        long t512 = TokenCodec.encode(0, 512, 0, 0, 0);
        assertThat(TokenCodec.decodeResourceId(t512)).isEqualTo(512);

        long t1023 = TokenCodec.encode(0, 1023, 0, 0, 0);
        assertThat(TokenCodec.decodeResourceId(t1023)).isEqualTo(1023);

        long t1024 = TokenCodec.encode(0, 1024, 0, 0, 0); // wraps (1024 & 0x3FF = 0)
        assertThat(TokenCodec.decodeResourceId(t1024)).isEqualTo(0);
    }
}
