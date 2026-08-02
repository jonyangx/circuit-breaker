## 业务规则：策略校验与 SLA 推导（Policy Validation & SLA Derivation）

* **子领域：** 策略校验与 SLA 推导（Policy Validation）
* **版本：** 1.0  · **作者：** 增量分析（analyze-code）  · **最近更新：** 2026-08-02

### 2. 业务规则目录

#### 2.1 计算规则
| 规则 ID | 规则名称 | 计算公式/算法 | 适用用例 | 来源 |
|---------|---------|--------------|---------|------|
| BR-080-sla-derivation | SLA→参数换算 | `qps=SLA_TPS×(0.7~0.9)`；`concurrency≈qps×p99RT(秒)`（Little's Law）；`errThreshold≈max(10×稳态错误率, 业务容忍)`；`minCalls` 20~100；`τ≈观察窗口×1~2` | UC-011 | docs/system/05 §5 |

#### 2.2 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-081-policy-spec-invariants | SLA 跨参数不变量 S1–S5 | S1 `qps<slaTps`；S2 `concurrency≥qps×RT`；S3 `minCalls/TPS≪τ`；S4 `errThreshold≫稳态错误率`；S5 `minCalls≥3`(ERROR)/`≥10`(WARN)。按 mask 分支，未启用跳过 | UC-012 | PolicySpec |
| BR-082-opt-in-validation | opt-in 构造期校验 | 仅当 `.sla(SlaFacts)` 附了 SLA 事实，`build()` 才跑 PolicySpec；ERROR 抛 IAE 阻断、WARN 不阻断；不附则完全跳过，零行为变化 | UC-012 | PolicyBuilder.enforceSlaInvariants |

**详细规则说明：**

##### BR-081-policy-spec-invariants
* **类型：** 约束（Constraint）
* **描述：** `PolicySpec` 对照 SLA 事实（`SlaFacts: slaTps/avgRtMs/p99RtMs/steadyErrorRatePpm`）检查参数**之间**的关系不变量——单字段校验（BR-007）查不到这些。
* **不变量与边界：**
  - **S1 余量**（checkHeadroom）：`qps≥slaTps`→ERROR；`>95% slaTps`→WARN。
  - **S2 Little's Law**（checkConcurrency）：`concurrency<qps×avgRT`→ERROR；`<qps×p99RT`→WARN。
  - **S3 样本攒齐**（checkSampleAccumulation）：攒样本时间≥τ→ERROR；`>τ×10%`→WARN。TPS 上界：开限流用 `qps`，否则退化为 `slaTps`（乐观）。
  - **S4 跳闸余量**（checkTripMargin）：`errThreshold≤稳态错误率`→ERROR；`<10×稳态`→WARN。
  - **S5 冷启动地板**（checkMinCallsFloor）：`minCalls<3`→ERROR（1~2 次早期失败即跳闸）；`<10`→WARN（地板过薄）。
* **触发条件：** `PolicyBuilder.build()` 附了 `sla()` → `enforceSlaInvariants` → `PolicySpec.check`。
* **违反后果（ERROR）：** 误配进入运行时——qps 超下游硬限、并发门误杀正常流量、熔断在正常错误率下跳闸、冷启动假跳闸。
* **关联用例：** UC-012
* **代码引用：** `PolicySpec.java:86`(check) / `:103`(isValid) / `:111/126/144/167/184`(S1–S5)

##### BR-082-opt-in-validation
* **类型：** 约束（Constraint）
* **描述：** SLA 校验是 opt-in：`slaFacts==null` 时 `enforceSlaInvariants` 直接返回，保证既有调用方零行为变化。
* **约束内容：** ERROR 级 finding 阻断 `build`（抛 `IllegalArgumentException`，消息含全部 findings）；WARN 不阻断（"是否容忍 p99 慢请求被并发门挡"是业务策略）。
* **关联用例：** UC-012
* **代码引用：** `PolicyBuilder.java:42`(sla) / `:78`(enforceSlaInvariants)

### 3. 约束条件
* `PolicySpec` 为**离线工具**，绝不在请求热路径；仅注册/热更新期触发。
* `SlaFacts` 是构造期元数据，不进入运行时 `ResourceConfig`（保持 config 纯参数）。

### 4. 成功标准
* 2000 TPS / p99 100ms / 99.9% 示例：`concurrency=80` 时 S2 给 WARN（抓出 p99 误杀），提至 160 后全 OK（`PolicySpecTest` 验证）。
* 不调 `sla()` 时现有 `PolicyBuilderTest` 全绿（opt-in 向后兼容）。

### 5. 规则依赖
```
BR-007-config-validation（单字段） → BR-081-policy-spec-invariants（跨参数） → BR-082-opt-in-validation（集成）
BR-080-sla-derivation → BR-081-policy-spec-invariants（推导结果被校验）
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-08-02 | 初始版本（新增子领域，登记 PolicySpec） | 工作树新增 PolicySpec + sla() 集成 | analyze-code 增量 |
