package dev.circuitbreaker.core.breaker;

import dev.circuitbreaker.core.ResourceConfig;
import dev.circuitbreaker.core.ResourceState;

/**
 * 时间衰减 EWMA 熔断 + 三态状态机 + 代际（BR-020/022/023/024/025）。
 * 关联用例：UC-005。
 * 位布局：ewmaState [gen:4|last:24|count:16|ppm:20]；breakerState [state:2|gen:4|endTime:58]。
 * 实现步骤：
 *   - tryAcquire(st,now)：CLOSED 放行；OPEN 到期 CAS→HALF_OPEN(gen+1) 唯一探路；HALF_OPEN 门闩放行单探路。
 *   - release(st,now,ok,cfg,verMatch)：HALF_OPEN→transition(ok?CLOSED:OPEN)；CLOSED→updateEwma(可能→OPEN)。
 *   - transition()：唯一改 generation 的入口；CAS breakerState gen=(gen+1)&0xF。
 *   - updateEwma()：读 gNow；代际不匹配→重播种，否则 alpha 衰减。
 */
public final class EwmaCircuitBreaker {
    static final int CLOSED = 0, OPEN = 1, HALF_OPEN = 2;

    /** @return true 放行/探路；false 阻断（-2） */
    public static boolean tryAcquire(ResourceState st, long nowMs) {
        throw new UnsupportedOperationException("TODO: 读 breakerState 按 CLOSED/OPEN/HALF_OPEN 分派（UC-005/BR-025）");
    }

    public static void release(ResourceState st, long nowMs, boolean ok, ResourceConfig cfg, boolean verMatch) {
        throw new UnsupportedOperationException("TODO: HALF_OPEN→transition；CLOSED→updateEwma+跳闸判定（UC-005/BR-020/024）");
    }

    static boolean transition(ResourceState st, int from, int to, long endTimeMs) {
        throw new UnsupportedOperationException("TODO: CAS breakerState，gen+1（唯一改代际入口，BR-024/025）");
    }

    static void updateEwma(ResourceState st, long nowMs, int xPpm) {
        throw new UnsupportedOperationException("TODO: 读 gNow，代际不匹配重播种/否则 EwmaAlpha 衰减（BR-020/021/022/024）");
    }
}
