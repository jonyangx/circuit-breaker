package dev.circuitbreaker.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 资源运行时状态（聚合根，跨版本稳定，永不随规则重建）。
 * 关联用例：UC-001（注册）；规则 BR-002（配置/状态分离）、BR-051（状态稳定）。
 * 实现步骤：持有 bucketState/breakerState/ewmaState AtomicLong + concurrency[] AtomicInteger + pass/block LongAdder。
 *   - 令牌桶/breakerState 建议 @Contended 填充隔离伪共享（design §6.1）。
 *   - 违反后果：在途 release 打到新对象计数器、并发变负（v1 根因缺陷）。
 */
public final class ResourceState {
    static final int SEG = 16;
    final AtomicLong bucketState  = new AtomicLong();
    final AtomicLong breakerState = new AtomicLong();
    final AtomicLong ewmaState    = new AtomicLong();
    final AtomicInteger[] concurrency = new AtomicInteger[SEG];
    final LongAdder passCount  = new LongAdder();
    final LongAdder blockCount = new LongAdder();

    public ResourceState() {
        throw new UnsupportedOperationException("TODO: 初始化 concurrency[SEG] 各 new AtomicInteger(0)");
    }

    public long sumConcurrency() {
        throw new UnsupportedOperationException("TODO: 求和 concurrency[]（供测试/导出，非热路径）");
    }
}
