package dev.circuitbreaker.core.ratelimit;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;

/**
 * 惰性无锁令牌桶（单 AtomicLong，不分段）。
 * 关联用例：UC-004；规则 BR-010（位布局）、BR-011（惰性补）、BR-012（不分段）、BR-013（抹零）。
 * 实现步骤：CAS 循环解包 Time_last/Tokens；add=(now-tLast)*ratePerMs；nTok=min(cap,tok+add)；
 *   nTok<1 时不推进 tLast（BR-013）返回阻断；否则 nTok-=1 后 CAS。
 */
public final class LazyTokenBucket {
    static final long TIME_BUCK = (1L << 22) - 1; // 低 22 位 Tokens
    static final int  TIME_SHIFT = 22;            // 高 42 位 Time

    /** @return true 放行；false 阻断（-3） */
    public static boolean tryAcquire(ResourceState st, ResourceConfig cfg, long nowMs) {
        throw new UnsupportedOperationException("TODO: CAS 惰性补令牌，仅完整令牌才推进 Time_last（UC-004/BR-011/013）");
    }
}
