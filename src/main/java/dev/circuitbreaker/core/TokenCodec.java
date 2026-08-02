package dev.circuitbreaker.core;

/**
 * 64-bit Token codec (BR-003). Layout (high → low):
 * [sign:1=0][time:37][version:10][bucketIdx:4][mask:12].
 * Branchless, allocation-free encode/decode via shift + mask.
 * UC-002 (acquire packs token), UC-003 (release decodes).
 *
 * <p>Version is 10 bits (wrap at 1024 hot-swaps) — widened from 6 bits (BR-052 ABA window).
 * The 4 bits are borrowed from {@code time} (41→37); 37 bits of ms ≈ 1.1 years still ≫ any RT,
 * so the modular-subtraction RT in {@link #rtMs(long, long)} stays correct.
 */
public final class TokenCodec {
    public static final int MASK_BITS = 12, BUCKET_BITS = 4, VERSION_BITS = 10, TIME_BITS = 37;
    public static final int MASK_SHIFT = 0, BUCKET_SHIFT = 12, VERSION_SHIFT = 16, TIME_SHIFT = 26;
    public static final long MASK_MASK = (1L << MASK_BITS) - 1;        // 0xFFF
    public static final long BUCKET_MASK = (1L << BUCKET_BITS) - 1;    // 0xF
    public static final long VERSION_MASK = (1L << VERSION_BITS) - 1;  // 0x3FF
    public static final long TIME_MASK = (1L << TIME_BITS) - 1;

    private TokenCodec() {}

    /** Pack token. TIME_MASK has 37 bits → shifted left 26 lands at bit62; sign bit63 stays 0. */
    public static long encode(long timeMs, int version, int bucketIdx, int mask) {
        return ((timeMs & TIME_MASK) << TIME_SHIFT)
             | ((version & VERSION_MASK) << VERSION_SHIFT)
             | ((bucketIdx & BUCKET_MASK) << BUCKET_SHIFT)
             | (mask & MASK_MASK);
    }

    public static long decodeTime(long token) {
        return (token >>> TIME_SHIFT) & TIME_MASK;
    }

    public static int decodeVersion(long token) {
        return (int) ((token >>> VERSION_SHIFT) & VERSION_MASK);
    }

    public static int decodeBucket(long token) {
        return (int) ((token >>> BUCKET_SHIFT) & BUCKET_MASK);
    }

    public static int decodeMask(long token) {
        return (int) (token & MASK_MASK);
    }

    /** RT via modular subtraction; correct as long as a single RT < 2^TIME_BITS ms. */
    public static long rtMs(long nowRelMs, long token) {
        return (nowRelMs - decodeTime(token)) & TIME_MASK;
    }
}
