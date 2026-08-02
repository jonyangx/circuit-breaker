# 代码分析工作计划 (Code Analysis Work Plan) — 增量更新

## 元数据 (Metadata)
- **生成时间**: 2026-08-02
- **项目语言**: java（Java 21）
- **源代码路径**: src/main/java/dev/circuitbreaker
- **模式**: 增量更新（现有 domain `001-circuit-breaker`，不新建 domain）
- **总 Domain 数**: 1（单一内聚流量治理库，单一限界上下文；多 Domain 准则不适用）
- **本次增量任务数**: 3
- **执行状态**: completed
- **最后更新时间**: 2026-08-02

## 执行进度概览 (Progress Overview)
- **已完成增量任务**: 3 / 3
- **总体完成度**: 100%
- **现有子领域**: 9（001-resource-lifecycle … 008-observability + 新增 009-policy-validation）

## 增量任务列表 (Increment Tasks)

### 任务 1: 新增 009-policy-validation 子领域 — completed
- **状态**: completed
- **触发**: 工作树新增 `PolicySpec.java`（S1–S5 离线 SLA 校验）+ `PolicyBuilder.sla()` opt-in 集成
- **产出**:
  - `docs/domains/001-circuit-breaker/usecases/009-policy-validation/usecase.md`（UC-011 SLA 推导 + UC-012 构造期校验）
  - `docs/domains/001-circuit-breaker/usecases/009-policy-validation/rules.md`（BR-080 SLA 换算 / BR-081 S1–S5 不变量 / BR-082 opt-in）

### 任务 2: 修正既有文档漂移 — completed
- **状态**: completed
- **触发**: C2 扩位后 `001/rules` BR-003（41/6）与 `data-model`（37/10）自相矛盾；`ratePerMs` 命名与代码 `qps` 不一致
- **产出**:
  - `001-resource-lifecycle/rules.md`：BR-003 位宽 41/6 → 37/10（表格行 + 详细说明 + TIME_SHIFT 22→26）
  - `artifacts/data-model.md`：§2 字段 `ratePerMs` → `qps`（与 `ResourceConfig.java` 一致）

### 任务 3: 更新 analyze-code-output + workplan — completed
- **状态**: completed
- **产出**:
  - `analyze-code-output.md`：Source Overview 加 `PolicySpec`；UC 表加 UC-011/012；BR 段加 BR-080~082；漂移表加 D6（PolicySpec 已登记）
  - `analyze-code-workplan.md`（本文件）

## 任务依赖关系
```
任务 1（新增 009） ──┐
任务 2（修漂移）   ──┼──→ 任务 3（汇总登记）
```

## 验证清单 (Validation Checklist)
- [x] `009-policy-validation/usecase.md` 与 `rules.md` 存在且完整
- [x] 用例文档仅引用规则 ID（BR-080~082），规则细节在 rules.md
- [x] BR-003 位宽与 `data-model.md`、`TokenCodec.java`（VERSION=10/TIME=37）一致
- [x] 未覆盖现有 8 个 usecases，未新建重复 domain
- [x] 子领域编号连续（009 紧随 008）

## 断点续做说明
本次增量 3 任务已全部 completed。若后续代码再次变更，重跑 `/ease:analyze-code` 将基于本 workplan 与现有 `001-circuit-breaker` 继续增量（扫描下一子领域编号、不重建既有内容）。
