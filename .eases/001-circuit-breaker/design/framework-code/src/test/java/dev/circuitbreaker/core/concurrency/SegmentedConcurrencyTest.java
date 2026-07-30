package dev.circuitbreaker.core.concurrency;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 分段并发控制测试（UC-006，BR-030/031/032）。
 * 关联测试案例：TC-CAP-CC-001..003、TC-API-003-002（跨线程回滚归零）。
 */
class SegmentedConcurrencyTest {
    @Test void blocksOverLimit() { fail("TODO: limit=10，20 并发约 10 放行其余 -4（BR-030）"); }
    @Test void rollsBackToZero() { fail("TODO: 全部 release 后 sum(concurrency)=0（BR-032）"); }
    @Test void routeIndexInBounds() { fail("TODO: bucketIdx ∈ [0,SEG)（BR-031）"); }
}
