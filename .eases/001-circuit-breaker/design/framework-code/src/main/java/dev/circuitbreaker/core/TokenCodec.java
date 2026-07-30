package dev.circuitbreaker.core;

/**
 * 64 位 Token 编解码（BR-003）。
 * 位布局：[sign:1=0][time:41][version:6][bucketIdx:4][mask:12]
 * 关联用例：UC-002（acquire 打包）、UC-003（release 解码）
 * 实现步骤：位宽/偏移/掩码为编译期常量；encode/decode 用移位+掩码，无分支、无对象。
 *   - BR-003：符号位恒 0（TIME 左移 22 后最高落 bit62）；RT 用模减法 (now-decodeTime)&TIME_MASK
 */
public final class TokenCodec {
    static final int MASK_BITS = 12, BUCKET_BITS = 4, VERSION_BITS = 6, TIME_BITS = 41;
    static final int MASK_SHIFT = 0, BUCKET_SHIFT = 12, VERSION_SHIFT = 16, TIME_SHIFT = 22;
    static final long MASK_MASK = (1L << MASK_BITS) - 1;          // 0xFFF
    static final long BUCKET_MASK = (1L << BUCKET_BITS) - 1;      // 0xF
    static final long VERSION_MASK = (1L << VERSION_BITS) - 1;    // 0x3F
    static final long TIME_MASK = (1L << TIME_BITS) - 1;

    private TokenCodec() {}

    /** 打包 token；符号位天然恒 0。 */
    public static long encode(long timeMs, int version, int bucketIdx, int mask) {
        throw new UnsupportedOperationException("TODO: 按 BR-003 移位+掩码打包 [time|version|bucket|mask]");
    }

    public static long decodeTime(long token) {
        throw new UnsupportedOperationException("TODO: (token >>> TIME_SHIFT) & TIME_MASK");
    }

    public static int decodeVersion(long token) {
        throw new UnsupportedOperationException("TODO: (token >>> VERSION_SHIFT) & VERSION_MASK");
    }

    public static int decodeBucket(long token) {
        throw new UnsupportedOperationException("TODO: (token >>> BUCKET_SHIFT) & BUCKET_MASK");
    }

    public static int decodeMask(long token) {
        throw new UnsupportedOperationException("TODO: (int)(token & MASK_MASK)");
    }

    /** RT 模减法，抗 time 字段截断/环绕。 */
    public static long rtMs(long nowRelMs, long token) {
        throw new UnsupportedOperationException("TODO: (nowRelMs - decodeTime(token)) & TIME_MASK");
    }
}
