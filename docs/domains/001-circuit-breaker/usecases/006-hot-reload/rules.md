## 业务规则：RCU 规则热更新（Hot Reload）

* **子领域：** 热更新（Hot Reload）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 业务规则目录

#### 2.1 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-050-rcu-config-swap | RCU 原子指针替换 | `CONFIGS` 用 volatile 数组/AtomicReferenceArray；热更新 `new` 不可变 Config（version+1）后整体替换 | UC-008 | design §8.2 |
| BR-051-state-stable | 状态跨版本稳定 | `STATES[resourceId]` 规则变更时永不重建；令牌桶/breaker/ewma/并发段/观测计数挂在稳定槽位 | UC-008 | design §8.1 |
| BR-052-version-check | release 版本校验 | release 从 token 解出 `version`，与当前 `CONFIGS.version`(低6位) 比对；版本已变时并发照常回滚、EWMA 上报降权/跳过 | UC-003, UC-008 | design §6.4 |

**详细规则说明：**

##### BR-051-state-stable
* **类型：** 约束（Constraint）
* **描述：** 对应 v1 根因缺陷：v1 把含状态的 LazyTokenBucket/EwmaCircuitBreaker 整体替换，导致在途 release 打到新计数器。
* **约束内容：** 热更新只 `CONFIGS.set`，`STATES[id]` 原地不动。
* **违反后果：** 在途 release 打到新对象计数器、并发计数变负、旧计数永不归零。

##### BR-052-version-check
* **类型：** 约束（Constraint）
* **描述：** release 版本校验三态处理。
* **版本一致：** 正常按当前配置上报。
* **版本已变：** 并发计数照常按 `bucketIdx` 回滚（稳定 state，永远正确）；EWMA 上报可选跳过/降权，避免旧阈值语义污染新配置。
* **关联用例：** UC-003, UC-008

### 3. 约束条件
* **无僵尸限流：** 因无任何后台调度挂载到旧配置，旧对象失引用即 GC，不会内存泄漏或"僵尸限流"。

### 4. 成功标准
* 热更新压力测试：在 acquire 与 release 之间高频热换，`concurrency` 段求和不漂移、不出现负值。
* 新 capacity 在下一次 acquire 生效（min 截断）。

### 5. 规则依赖
```
BR-002-config-state-separation → BR-050-rcu-config-swap / BR-051-state-stable
BR-003-token-encoding(version) → BR-052-version-check
BR-052-version-check → UC-003(release 异常流程 4a)
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |
