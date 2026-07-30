package dev.circuitbreaker.core.system;

/**
 * 系统级分级概率丢弃 + 迟滞（BR-040/041/042）。
 * 关联用例：UC-007。
 * 实现步骤：低频探针线程（1s）采 CPU，按分级阈值（含迟滞）写 volatile SHED_PERMILLE（0/200/500/800‰）。
 *   - SHED_PERMILLE 由 FlatExecutionEngine 前置短路读取（单次 volatile 读，不入热路径自旋）。
 *   - BR-042：探针非热路径（constitution 不变量4 允许的低频后台例外）。
 */
public final class SystemOverload {
    static volatile int SHED_PERMILLE = 0;

    /** 探针线程更新（分级 + 迟滞）。 */
    static void refreshProbe() {
        throw new UnsupportedOperationException("TODO: 采 CPU→按迟滞阈值更新 SHED_PERMILLE（UC-007/BR-041）");
    }
}
