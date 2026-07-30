## 业务规则：系统过载分级丢弃（Graded Load Shedding）

* **子领域：** 系统过载（System Overload）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 业务规则目录

#### 2.4 动作触发规则
| 规则 ID | 规则名称 | 触发条件 | 触发动作 | 适用用例 | 来源 |
|---------|---------|---------|---------|---------|------|
| BR-040-graded-shed-permille | 分级概率丢弃 | `SHED_PERMILLE>0` 且随机 < shed | 返回 `BLOCK_SYSTEM_OVERLOAD(-1)`，不进入资源策略 | UC-007 | design §10.2 |
| BR-041-hysteresis | 迟滞档位 | CPU 分级进退档（进档阈值>退档阈值） | 探针更新 `SHED_PERMILLE`（0/200/500/800‰） | UC-007 | design §10.1 |

#### 2.1 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-042-probe-off-path | 探针非热路径 | CPU 探针为低频（1s）后台线程，结果经 `volatile SHED_PERMILLE` 单次读入热路径；探针本身不在请求关键路径 | UC-007 | design §10 / constitution 不变量4 |

**详细规则说明：**

##### BR-040-graded-shed-permille
* **类型：** 动作触发（Action Enabler）
* **触发条件：** `shed = SHED_PERMILLE > 0` 且 `ThreadLocalRandom.current().nextInt(1000) < shed`。
* **触发动作：** 直接返回 `-1`，O(1) 概率拦截。
* **执行时机：** tryAcquire 入口，先于资源级校验。
* **关联用例：** UC-007

##### BR-041-hysteresis
* **类型：** 动作触发（Action Enabler）
* **触发条件：** CPU 进入某档（如 >85% 持续 3s）/ 退出某档（如 <75%）。
* **触发动作：** 更新 `SHED_PERMILLE` 到对应档（200/500/800‰）。
* **设计意图：** 相比 v1 全量拒绝，分级丢弃在过载边缘平滑降载，避免"全放行→全拒绝→全放行"自激振荡。

### 3. 约束条件
* **唯一后台线程例外：** 系统探针是 constitution 不变量 4 明确允许的低频后台线程，但因不进入请求关键路径，不违反"治理侧无定时器"结论。

### 4. 成功标准
* 过载边缘：CPU 持续高位时 `SHED_PERMILLE` 升档，部分请求被 `-1`，且无震荡（迟滞验证）。
* 正常负载：`SHED_PERMILLE=0`，零拦截、零开销。

### 5. 规则依赖
```
BR-041-hysteresis → BR-040-graded-shed-permille(SHED_PERMILLE 由探针写入)
BR-042-probe-off-path → BR-040(volatile 单读入热路径)
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |
