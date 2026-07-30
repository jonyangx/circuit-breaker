# 技术计划（Phase 2）：Circuit Breaker

**特性分支**：`001-circuit-breaker` | **日期**：2026-07-30
**权威技术计划**：`../flow-1-analyze/plan.md`（Gradle 多模块结构/技术栈/宪章检查）
**本文件作用**：Phase 2 增强版 plan 阶段产物——叠加框架代码生成计划。

## 技术栈（与宪章对齐）
Java 21+ / Gradle (Kotlin DSL) / JMH / JUnit5+AssertJ+JaCoCo / groupId `dev.circuitbreaker`。

## 框架代码生成清单（Phase 2 已产出）
见 `framework-code/framework-code-manifest.md`（18 生产骨架）+ `framework-code/test-framework-manifest.md`（10 测试骨架，TDD fail 占位）。

骨架位于 `framework-code/src/{main,test}/java/dev/circuitbreaker/...`，方法体 `throw UnsupportedOperationException("TODO")`、测试 `fail("TODO")`，均引用 UC/BR。

## 模块结构（Phase 3 落地）
```
circuit-breaker-core/        ← 共享内核 + 四能力（零三方依赖）
circuit-breaker-reactive/    ← reactor-core
circuit-breaker-observability/ ← prometheus simpleclient
circuit-breaker-benchmarks/  ← JMH
```
Phase 3 将骨架迁入各模块 `src/main/java`、实现 TODO。

## 实现步骤分解（指向权威 tasks.md）
权威任务清单：`../flow-1-analyze/tasks.md`（42 任务，10 阶段，TDD）。Phase 2 已完成框架骨架（对应搭建+基础+各故事骨架），Phase 3 仅实现 TODO（消费权威 tasks，就地回写 [X]，不重跑生命周期）。
