package dev.circuitbreaker.core;

/**
 * 64-bit Token codec (BR-003). Layout (high → low):
 * [sign:1=0][time:36][version:10][bucketIdx:4][resourceId:10][mask:3].
 * Branchless, allocation-free encode/decode via shift + mask.
 * UC-002 (acquire packs token), UC-003 (release decodes).
 *
 * <p>Version is 10 bits (wrap at 1024 hot-swaps) — widened from 6 bits (BR-052 ABA window).
 *
 * <p><b>resourceId is embedded</b> (BR-053): release() decodes it and rejects a token whose
 * embedded resourceId ≠ the passed resourceId. This closes the cross-resource-release hole the
 * mask-only layout left open: in a reactive pipeline (Reactor/Netty) a caller could otherwise hand
 * resource A's token to {@code release(resourceB, …)} and silently corrupt B's counters (decrement
 * the wrong concurrency segment, update the wrong EWMA). bucketIdx in the token already made
 * release thread-agnostic; resourceId makes it resource-accurate. The 10-bit field covers all
 * {@link ResourceManager#MAX_RESOURCES} (=1024) IDs.
 *
 * <p>Bit budget: 1 sign + 36 time + 10 version + 4 bucket + 10 resourceId + 3 mask = 64.
 * resourceId takes 9 bits from the previously-12-bit mask field (only 3 capability bits 0x01/0x02/0x04
 * are defined) plus 1 bit borrowed from time (37→36). 36 bits of ms ≈ 824 days still ≫ any RT,
 * so the modular-subtraction RT in {@link #rtMs(long, long)} stays correct.
 */
public final class TokenCodec {
    public static final int MASK_BITS = 3, BUCKET_BITS = 4, RESOURCE_BITS = 10, VERSION_BITS = 10, TIME_BITS = 36;
    public static final int MASK_SHIFT = 0, RESOURCE_SHIFT = 3, BUCKET_SHIFT = 13, VERSION_SHIFT = 17, TIME_SHIFT = 27;
    public static final long MASK_MASK = (1L << MASK_BITS) - 1;        // 0x7
    public static final long RESOURCE_MASK = (1L << RESOURCE_BITS) - 1; // 0x3FF (covers MAX_RESOURCES=1024)
    public static final long BUCKET_MASK = (1L << BUCKET_BITS) - 1;    // 0xF
    public static final long VERSION_MASK = (1L << VERSION_BITS) - 1;  // 0x3FF
    public static final long TIME_MASK = (1L << TIME_BITS) - 1;

    private TokenCodec() {}

    /**
     * Pack token. TIME_MASK has 36 bits → shifted left 27 lands at bit62; sign bit63 stays 0.
     *
     * @param resourceId the resource that owns this token (written into the token so release()
     *                   can detect cross-resource misuse — BR-053)
     */
    public static long encode(long timeMs, int resourceId, int version, int bucketIdx, int mask) {
        return ((timeMs & TIME_MASK) << TIME_SHIFT)
             | ((version & VERSION_MASK) << VERSION_SHIFT)
             | ((bucketIdx & BUCKET_MASK) << BUCKET_SHIFT)
             | ((resourceId & RESOURCE_MASK) << RESOURCE_SHIFT)
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

    /** Decode the embedded resource owner (BR-053); compare to the release() argument. */
    public static int decodeResourceId(long token) {
        return (int) ((token >>> RESOURCE_SHIFT) & RESOURCE_MASK);
    }

    public static int decodeMask(long token) {
        return (int) (token & MASK_MASK);
    }

    /** RT via modular subtraction; correct as long as a single RT < 2^TIME_BITS ms. */
    public static long rtMs(long nowRelMs, long token) {
        return (nowRelMs - decodeTime(token)) & TIME_MASK;
    }
}
