## 业务规则：资源与生命周期（Resource & Lifecycle）

* **子领域：** 资源与生命周期（Resource & Lifecycle）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

---

### 2. 业务规则目录

#### 2.1 约束规则（Constraints）

| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-001-resource-id-int | 整数 resourceId 寻址 | 资源用全局唯一整数 `resourceId`（0..1023）标识，经 `CONFIGS[]`/`STATES[]` 数组寻址，禁止 Map 查找 | UC-001 | design §3.3 |
| BR-002-config-state-separation | 配置/状态分离 | `CONFIGS`（不可变、可 RCU 热换）与 `STATES`（长生命周期、规则变更时永不重建）分离；严禁把可变运行时状态耦合进可替换 Config | UC-001, UC-008 | design §3.1 / §8 |
| BR-003-token-encoding | 64 位 token 位布局 | `[sign:1][time:41][version:6][bucketIdx:4][mask:12]`，符号位恒 0；位宽/偏移为编译期常量 | UC-002, UC-003 | design §3.2.1 |
| BR-004-block-code-negative | 阻断码全负 | `BLOCK_SYSTEM_OVERLOAD=-1`、`BLOCK_CIRCUIT_BREAKER=-2`、`BLOCK_RATE_LIMITER=-3`、`BLOCK_CONCURRENCY=-4`；`token<0` 即阻断。**块码→类型化异常经统一 `GovernanceException.forToken/throwFor`（base + 4 子类）；reactive `CircuitBreakerOperator` 亦经此映射，不再用独立异常类（代码实现更新，对抗性审查 D4）** | UC-002 | design §4.1 |
| BR-005-bitmask-dispatch | 位掩码分派 | 按 `config.mask` 依次位与，相应位为 1 调用对应模块，任一失败即返回对应阻断码 | UC-002 | design §4.1 |
| BR-006-monotonic-nanotime | 单调相对时钟 | 统一 `Time_now = System.nanoTime()/1_000_000 - START`；禁止 `currentTimeMillis()` 做治理判定 | 全部 | design §6.3 |
| BR-007-config-validation | 配置入参校验 | `PolicyBuilder.build()` 校验：errThreshold∈(0,1]、ewmaTauMs>0、minCalls>0、qps∈(0,4_194_303]、concurrencyLimit>0、openMillis>0；违例抛 IllegalArgumentException（防止 always-trip/never-trip 等误配踩雷）（代码实现更新，对抗性审查 A2） | UC-001 | 实现 PolicyBuilder.build |

**详细规则说明：**

##### BR-002-config-state-separation
* **类型：** 约束（Constraint）
* **描述：** 把「可热更新的纯参数」与「长生命周期运行时状态」放在两个独立生命周期的容器里。热更新只替换配置，状态跨版本稳定存活。
* **触发条件：** 任何资源注册、规则热更新、release 回写。
* **约束内容：** `ResourceConfig` 不可变且整体替换；`ResourceState` 永不因规则变更而重建。
* **违反后果：** 在途 release 打到新对象计数器、并发计数变负、旧计数永不归零（v1 根因缺陷）。
* **关联用例：** UC-001, UC-003, UC-008
* **代码引用：** `CONFIGS[]` / `STATES[]`

##### BR-003-token-encoding
* **类型：** 约束（Constraint）
* **描述：** 64 位 long 位布局自低向高：mask(12)/bucket(4)/version(6)/time(41)/sign(1)。encode/decode 用移位+掩码，无分支、无对象。
* **触发条件：** acquire 打包 token、release 解码。
* **约束内容：** TIME_MASK 左移 22 后最高落 bit62，符号位天然恒 0；RT 用模减法 `(now - decodeTime) & TIME_MASK`。
* **违反后果：** release 跨线程扣错桶、RT 计算错误、阻断判定失效。
* **关联用例：** UC-002, UC-003

---

### 3. 约束条件
* **位宽让位策略：** 掩码位 >12 时从 `time` 字段借位（time 27 位≈37h 仍覆盖任何 RT）；宁可借 time，不可压缩 mask（退回多态分派）。影响：UC-002。原因：mask 是 O(1) 位与判定基础。

### 4. 成功标准
* **token 自描述正确：** 跨线程 release 后 `concurrency` 段求和归零（断言）。
* **零分配：** acquire/release 热路径 JMH `-gc` 零字节（`BR-NFR-perf`）。

### 5. 规则依赖关系
```
BR-006-monotonic-nanotime → BR-003-token-encoding(time 字段)
BR-002-config-state-separation → BR-005-bitmask-dispatch(config.mask)
BR-003-token-encoding → BR-004-block-code-negative(符号位)
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |

### 7. 附录
* **术语：** 见 `docs/domains/001-circuit-breaker/analyze-brd-output.md` Glossary。
* **参考：** `docs/brd/design.md` §3、§4.1、§6.3、§8。
