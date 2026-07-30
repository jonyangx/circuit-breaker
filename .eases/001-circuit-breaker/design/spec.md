# 设计规范（Phase 2）：Circuit Breaker

**特性分支**：`001-circuit-breaker` | **日期**：2026-07-30 | **状态**：Draft
**权威需求规范**：`../flow-1-analyze/spec.md`（7 用户故事 / FR-001..012 / SC-001..006）
**本文件作用**：Phase 2 增强版 specify 阶段产物——在 Phase 1 规范基础上叠加架构决策与澄清结论。

## 设计目标（与 Phase 1 一致）
纳秒级、零分配、无锁、无治理侧定时线程的流量治理组件（熔断/限流/并发/系统过载）+ reactive 适配 + Prometheus 导出。

## 架构决策（clarify 阶段确认）

> greenfield + 宪章已锁定技术栈，核心决策在 Phase 1 拷问 + 宪章中已定；Phase 2 clarify 无未决冲突（new-domain 无 UC 冲突）。记录的关键架构决策：

1. **架构模式**：扁平化执行引擎 + bit-packed 原始类型状态（非 OOP 责任链）。理由：不变量1/2/3。
2. **公共 API**：7 个 Java 公共方法（API-001..007），非 HTTP；阻断以负 long 表达（不抛异常，避免栈分配）。
3. **模块**：core（零三方依赖）+ reactive + observability + benchmarks 四 Gradle 模块。
4. **HALF_OPEN 探路门闩**：复用 breakerState 借位做 in-flight 门闩（detail-design §3.3 选定）。
5. **并发分段**：SEG=16 编译期常量。

## Clarifications
- v1 范围/排除项/性能门槛/reactive+observability：见 `../flow-1-analyze/spec.md` §Clarifications（Phase 1 拷问结论）。
- 无 [NEEDS CLARIFICATION]（greenfield + 设计自洽）。

## 成功标准（不变，SC-001..006）
见 `../flow-1-analyze/spec.md`。Phase 2 新增可验证设计契约：位布局（detail-design §2）、接口面（api-interface-report §1.2，覆盖率 100%）。
