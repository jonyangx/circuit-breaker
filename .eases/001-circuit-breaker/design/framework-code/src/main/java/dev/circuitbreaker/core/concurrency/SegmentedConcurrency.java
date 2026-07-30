package dev.circuitbreaker.core.concurrency;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 分段近似并发控制（AtomicInteger[SEG]，TLR probe 路由）。
 * 关联用例：UC-006；规则 BR-030（分段）、BR-031（TLR probe 非 threadId）、BR-032（bucketIdx 回滚同段）。
 * 实现步骤：
 *   - tryAcquire：bidx = TLR.nextInt() & (SEG-1)；sum>=limit 返回 -1（阻断）；否则 concurrency[bidx]++ 返回 bidx。
 *   - release：concurrency[bidx]--（线程无关，bidx 来自 token）。
 */
public final class SegmentedConcurrency {
    /** @return >=0 路由段（写入 token）；<0 阻断（-4） */
    public static int tryAcquire(ResourceState st, ResourceConfig cfg) {
        throw new UnsupportedOperationException("TODO: TLR probe 路由 + sum 判定 + 段自增（UC-006/BR-031/030）");
    }

    public static void release(ResourceState st, int bucketIdx) {
        throw new UnsupportedOperationException("TODO: concurrency[bucketIdx].decrementAndGet（UC-006/BR-032）");
    }
}
