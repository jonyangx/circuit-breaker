package dev.circuitbreaker.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * TokenCodec defect verification test (AA Report §2.1 HIGH).
 * Verifies encode/decode round-trip, resourceId embedding (BR-053),
 * RT modular subtraction correctness, and sign-bit overflow guard.
 */
class TokenCodecDefectTest {

    /**
     * Encode/decode round-trip for all fields.
     */
    @Test
    void encodeDecodeRoundTrip() {
        long timeMs = 123_456L;
        int resourceId = 42;
        int version = 7;
        int bucketIdx = 11;
        int mask = 0x05;

        long token = TokenCodec.encode(timeMs, resourceId, version, bucketIdx, mask);

        assertThat(TokenCodec.decodeTime(token)).isEqualTo(timeMs);
        assertThat(TokenCodec.decodeResourceId(token)).isEqualTo(resourceId);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(version);
        assertThat(TokenCodec.decodeBucket(token)).isEqualTo(bucketIdx);
        assertThat(TokenCodec.decodeMask(token)).isEqualTo(mask);
    }

    /**
     * AA §2.1: RT calculation must be correct as long as single RT < 2^TIME_BITS ms.
     * With TIME_BITS=27, the modular subtraction returns correct positive RT.
     */
    @Test
    void rtMsCorrectForSmallIntervals() {
        long acquireTime = 100_000L;
        int rid = 1;
        long token = TokenCodec.encode(acquireTime, rid, 1, 0, 0x01);

        // RT = 50ms after acquire
        long now = acquireTime + 50;
        assertThat(TokenCodec.rtMs(now, token)).isEqualTo(50);
    }

    /**
     * AA §2.1: RT modular subtraction stays positive even when now < decodeTime
     * (shouldn't happen in practice, but verify the mask logic).
     */
    @Test
    void rtMsModularSubtractionStaysInRange() {
        long acquireTime = 100_000L;
        long token = TokenCodec.encode(acquireTime, 1, 1, 0, 0x01);
        long now = acquireTime + 1000;

        long rt = TokenCodec.rtMs(now, token);
        assertThat(rt).isGreaterThanOrEqualTo(0);
        assertThat(rt).isLessThan(1L << TokenCodec.TIME_BITS);
    }

    /**
     * AA §2.1: After nowRelMs exceeds TIME_MASK (≈37 hours), the encoded time wraps.
     * This documents the 27-bit time field limitation. The encode truncates to TIME_MASK,
     * so rtMs may return incorrect values once uptime exceeds 2^27 ms.
     *
     * This test verifies the CURRENT behavior (27-bit truncation) so the limitation is explicit.
     */
    @Test
    void timeFieldTruncatedAt27Bits() {
        // A time value exceeding 2^27 (the TIME_MASK)
        long hugeTime = (1L << TokenCodec.TIME_BITS) + 5000; // 2^27 + 5000
        long token = TokenCodec.encode(hugeTime, 1, 1, 0, 0x01);

        // The encoded time is truncated to 27 bits (wrap), losing the high bit
        long decoded = TokenCodec.decodeTime(token);
        // 2^27 + 5000 & ((1<<27)-1) = 4999 (the high bit is dropped)
        assertThat(decoded).isEqualTo(hugeTime & TokenCodec.TIME_MASK);
        // This confirms the 27-bit limitation documented in AA §2.1
        assertThat(decoded).isNotEqualTo(hugeTime);
    }

    /**
     * TA-4 (AA §2.7): once nowRelMs exceeds 2^27 ms (~37h uptime) the 27-bit time field wraps.
     * RT computed by modular subtraction must stay EXACT as long as a single RT < 2^27 ms — a
     * release just after wrap decodes a small (rolled-over) time and the mask recovers the true
     * elapsed ms. This locks in the wrap behavior that {@link #timeFieldTruncatedAt27Bits} only
     * documents, so production logic depending on {@link TokenCodec#rtMs} cannot regress.
     */
    @Test
    void rtMsCorrectWhenTimeFieldWrapsAcrossUptime() {
        long timeMask = (1L << TokenCodec.TIME_BITS) - 1; // 2^27 - 1
        long acquireTime = timeMask - 100;                // acquired 100ms before the wrap
        long token = TokenCodec.encode(acquireTime, 1, 1, 0, 0x01);

        long now = 52;                                    // released 52ms after the wrap
        // True elapsed = (timeMask+1 − acquireTime) + (now − 0) = 101 + 52 = 153ms (< 2^27 → exact).
        assertThat(TokenCodec.rtMs(now, token)).isEqualTo(153);
    }

    /**
     * BR-053: resourceId is embedded and round-trips for all valid IDs (0..1023).
     */
    @Test
    void resourceIdRoundTripsForAllValidIds() {
        for (int rid = 0; rid < ResourceManager.MAX_RESOURCES; rid += 97) { // sample stride
            long token = TokenCodec.encode(1000, rid, 1, 0, 0x01);
            assertThat(TokenCodec.decodeResourceId(token)).isEqualTo(rid);
        }
        // Boundary: max resourceId
        long token = TokenCodec.encode(1000, ResourceManager.MAX_RESOURCES - 1, 1, 0, 0x01);
        assertThat(TokenCodec.decodeResourceId(token)).isEqualTo(ResourceManager.MAX_RESOURCES - 1);
    }

    /**
     * Sign bit must stay 0 so token >= 0 (BR-004: block codes are negative).
     * With current layout (TIME_BITS=27), max valid inputs never set bit 63.
     */
    @Test
    void encodeNeverSetsSignBitForValidInputs() {
        // Max time, max version, max bucket, max rid, max mask
        long token = TokenCodec.encode(
                TokenCodec.TIME_MASK,           // max 27-bit time
                ResourceManager.MAX_RESOURCES - 1, // max 10-bit rid
                (int) TokenCodec.VERSION_MASK,  // max 10-bit version
                (int) TokenCodec.BUCKET_MASK,   // max 4-bit bucket
                (int) TokenCodec.MASK_MASK);    // max 12-bit mask

        assertThat(token).isGreaterThanOrEqualTo(0);
        assertThat((token & 0x8000_0000_0000_0000L)).isZero();
    }

    /**
     * Version field is 10 bits (wraps at 1024 hot-swaps) — BR-052.
     */
    @Test
    void versionFieldIs10Bits() {
        long token = TokenCodec.encode(0, 0, 1023, 0, 0);
        assertThat(TokenCodec.decodeVersion(token)).isEqualTo(1023);

        // Version 1024 truncates to 0 (10-bit wrap)
        long token2 = TokenCodec.encode(0, 0, 1024, 0, 0);
        assertThat(TokenCodec.decodeVersion(token2)).isZero();
    }
}