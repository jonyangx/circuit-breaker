package dev.circuitbreaker.core;

/**
 * 单调相对时钟（BR-006）。
 * 关联用例：全部治理判定（UC-002/003/004/005/007）。
 * 实现步骤：启动记录 START = System.nanoTime()/1_000_000；nowRelMs() = nanoTime/1M - START。
 *   - BR-006：禁止 currentTimeMillis() 做治理判定。
 */
public final class ClockSource {
    static final long START = System.nanoTime() / 1_000_000L;

    private ClockSource() {}

    /** 相对单调毫秒时间戳（适配 token 41 位 time 字段）。 */
    public static long nowRelMs() {
        throw new UnsupportedOperationException("TODO: System.nanoTime()/1_000_000 - START");
    }
}
