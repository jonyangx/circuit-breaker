## 业务规则：响应式流量治理（Reactive Adapter）

* **子领域：** 响应式适配（Reactive Adapter）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 业务规则目录

#### 2.1 约束规则
| 规则 ID | 规则名称 | 规则描述 | 适用用例 | 来源 |
|---------|---------|---------|---------|------|
| BR-060-thread-agnostic-release | release 不依赖线程 | token 内含 bucketIdx+version，release 据此回段/校验，与执行线程无关；杜绝 Reactor/Netty 线程切换导致的计数漂移 | UC-009 | design §9 |
| BR-061-token-closure | token 闭包捕获 | token 是 64 位 long 局部变量，被闭包捕获传递，不绑定 ThreadLocal，无上下文丢失/泄漏 | UC-009 | design §9 |
| BR-062-防御-synchronous-exception | 同步异常不泄漏 | source.get() 同步抛异常时 Mono 不存在、doFinally 永不触发；必须 try-catch 并在异常时 release(token, CANCELLED) 后重抛 | UC-009 | **P1 修复**（对抗性审查） |

**详细规则说明：**

##### BR-060-thread-agnostic-release
* **类型：** 约束（Constraint）
* **描述：** 把 Sentinel 的 ThreadLocal 依赖历史包袱彻底消除：生命周期全压进 token。
* **触发条件：** 任意 release 调用（尤其 acquire/release 不同线程）。
* **约束内容：** release 仅依赖 token 解码值（bucketIdx/version/mask/time）+ 稳定 STATES，不读取任何线程局部状态。
* **违反后果：** 响应式框架下并发计数漂移、EWMA 错报。
* **关联用例：** UC-009（并强化 UC-003, UC-006）

##### BR-061-token-closure
* **类型：** 约束（Constraint）
* **描述：** token 作为基本类型被闭包捕获，天然线程安全、无泄漏。
* **约束内容：** 禁止把任何可变上下文对象（Context/Entry）作为传递载体；唯一载体是 long token。

##### BR-062-防御-synchronous-exception
* **类型：** 约束（Constraint）
* **描述：** `CircuitBreakerOperator.wrap` 用 try-catch 包裹 `source.get()`。若 supplier 同步抛 RuntimeException/Error（如输入校验失败），Mono 根本不存在，`doFinally` 永不触发——并发槽位永久泄漏。catch 分支以 `Outcome.CANCELLED` 释放槽位（不污染 EWMA）后重抛原异常。
* **触发条件：** `source.get()` 同步抛异常（supplier 逻辑而非 Mono 内异常）。
* **违反后果：** 对抗场景下（supplier 校验输入即抛），并发槽位随每次调用递增、永不回收，最终资源耗尽。
* **关联用例：** UC-009；与 AA Defect 1（CANCELLED 不喂 EWMA）同族。
* **回归测试：** `synchronousSupplierExceptionReleasesSlot`、`synchronousSupplierErrorReleasesSlot`。

### 3. 约束条件
* **核心库即 reactive-safe：** reactive-adapter 模块是便捷层，正确性根基在核心 token 自描述（BR-003/BR-032）。

### 4. 成功标准
* 跨 Reactor 线程 acquire/release 后，并发段求和归零、EWMA 正确上报（集成测试）。
* 无 ThreadLocal 残留（代码静态检查）。

### 5. 规则依赖
```
BR-003-token-encoding(bucketIdx/version) → BR-060-thread-agnostic-release
BR-032-bucket-idx-rollback → BR-060-thread-agnostic-release
BR-060-thread-agnostic-release → BR-061-token-closure
```

### 6. 规则变更历史
| 版本 | 日期 | 变更内容 | 变更原因 | 变更人 |
|------|------|---------|---------|--------|
| 1.0 | 2026-07-30 | 初始版本 | - | Phase 1 |
| 1.1 | 2026-08-05 | 新增 BR-062（同步异常不泄漏），P1 对抗性修复 | source.get() 同步异常致槽位永久泄漏 | 代码审查 |
