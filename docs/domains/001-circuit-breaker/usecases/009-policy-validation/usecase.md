## 用例：策略校验与 SLA 推导（Policy Validation & SLA Derivation）

### 1. 头部与元数据
* **用例 ID：** UC-011 / UC-012
* **用例名称：** 从 SLA 推导治理参数 / 构造期 SLA 不变量校验
* **版本：** 1.0  · **作者：** 增量分析（analyze-code）  · **最近更新：** 2026-08-02
* **新增背景：** 工作树新增 `PolicySpec`（离线跨参数校验器，`PolicySpec.java`）+ `PolicyBuilder.sla()` opt-in 集成。

### 2. 核心信息
* **主要参与者：** 接入该库的中间件/业务开发工程师（Developer）、运维（Ops，提供 SLA 事实）
* **次要参与者：** PolicyBuilder、PolicySpec
* **目标：** 让业务方仅凭服务提供方 SLA（TPS / RT / 可用性）即可推导出正确的 `ResourceConfig` 参数，并在**构造期**自动拦截违反不变量的误配——而非等运行时异常表现。
* **层级：** Subfunction（支撑 UC-001 注册）

---

### UC-011 从 SLA 推导治理参数

* **触发器：** 接入方拿到下游 SLA（TPS 上限、p50/p99 RT、可用性），需换算为 `ResourceConfig` 参数。
* **前置条件：** 已知 SLA 的 TPS 上限（必须）；RT、可用性可选但强烈建议。
* **后置条件：** 得到一组满足 SLA 不变量（S1–S5）的参数草案。

#### 主成功场景
1. **Developer：** 取 SLA 的 TPS 上限，按安全系数换算 `qps = SLA_TPS × (0.7~0.9)`（留余量，避免踩下游硬限）。（Use BR-080）
2. **Developer：** 由 RT 经 Little's Law 推导 `concurrencyLimit ≈ qps × p99RT(秒)`（用 p99 不误杀慢请求）。
3. **Developer：** 由可用性得稳态错误率 `(1 − 可用性) × 1e6`，取 `errThreshold ≈ max(10 × 稳态错误率, 业务容忍)`。
4. **Developer：** 选 `minCalls`（20~100，低 TPS 取小值）与 `ewmaTauMs`（观察窗口 ×1~2），满足 `minCalls / qps(秒) ≪ τ`。
5. **Developer：** 组装 PolicyBuilder 链并 `.sla(SlaFacts)` 附 SLA 事实，`build()` 进入 UC-012 校验。

#### 异常流程
* **2a. 缺 RT：** 退化为仅用 TPS 推 qps，并发参数留待压测后定（S2 无法校验，并发门跳过）。
* **3a. 稳态错误率未知：** errThreshold 取经验值（如 10%），S4 仅给 WARN。

#### 非功能性需求
* **非热路径：** SLA 推导与校验仅在注册/热更新期，零请求路径开销。

---

### UC-012 构造期 SLA 不变量校验

* **触发器：** `PolicyBuilder.build()`（附了 `.sla(SlaFacts)` 时）末尾 `enforceSlaInvariants` → `PolicySpec.check`。
* **前置条件：** 已 `.sla(SlaFacts)`；单字段校验（BR-007）已通过。
* **后置条件：** 无 ERROR 级 finding → 返回不可变 `ResourceConfig`；有 ERROR → 抛 `IllegalArgumentException`（消息含全部 findings）。

#### 主成功场景
1. **PolicySpec：** 按 `cfg.mask` 分支，仅校验已启用能力对应的不变量（未启用跳过）。
2. **PolicySpec：** 依次评估 S1 余量 / S2 Little's Law / S3 样本攒齐 / S4 跳闸余量 / S5 minCalls 地板。（Use BR-081）
3. **PolicySpec：** 收集 finding（OK / WARN / ERROR）。
4. **PolicyBuilder：** 若无 ERROR，返回 config；WARN 不阻断（业务策略选择）。

#### 异常流程
* **2a. ERROR 级违例**（如 `qps≥slaTps`、`concurrency<qps×avgRT`、`minCalls<3`、`errThreshold≤稳态错误率`）：`build()` 抛 `IllegalArgumentException`，拒绝产出错误配置。
* **4a. 仅 WARN**（如 concurrency 低于 `qps×p99RT`、`minCalls<10`）：不阻断；调用方可单独 `PolicySpec.check` 查看诊断后决定是否调整。

#### 非功能性需求
* **opt-in：** 未调 `.sla()` 则 `enforceSlaInvariants` 直接返回，行为与历史完全一致（向后兼容）。
* **可观测：** `PolicySpec.check(cfg, sla)` / `isValid(cfg, sla)` 可独立调用，返回完整诊断供日志/启动检查。

---

### 5. 其他要求
* **关键业务规则：** BR-080-sla-derivation、BR-081-policy-spec-invariants、BR-082-opt-in-validation（详见同目录 `rules.md`）。
* **代码引用：** `PolicySpec.java`（check@86 / isValid@103 / S1@111 / S2@126 / S3@144 / S4@167 / S5@184）、`PolicyBuilder.java`（sla@42 / enforceSlaInvariants@78）。
* **关联：** 校验通过后参数进入 UC-001 注册；SLA→参数换算表详见 `docs/system/05_CONFIG_MANAGEMENT.md` §5。
