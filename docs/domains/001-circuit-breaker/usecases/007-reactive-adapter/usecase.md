## 用例：响应式流量治理（Reactive Adapter）

### 1. 头部与元数据
* **用例 ID：** UC-009
* **用例名称：** Reactor/WebFlux 跨线程流量治理（v1 独立 adapter 模块）
* **版本：** 1.0  · **作者：** Phase 1 分析  · **最近更新：** 2026-07-30

### 2. 核心信息
* **主要参与者：** 业务开发工程师（使用 WebFlux/Reactor/Vert.x）
* **目标：** 把生命周期上下文压进 64 位 token，使 acquire 与 release 可在不同 Reactor 线程执行而不上下文错乱/计数漂移。
* **层级：** User-Goal

### 3. 上下文与触发器
* **触发器：** 业务在 `Mono.defer` / 异步链中调用 acquire，在 `doOnSuccess`/`doOnError` 中 release。
* **前置条件：** 资源已注册；token 已携带 version+bucketIdx。
* **后置条件：** 无论 Reactor 线程池如何切换，token 作为闭包捕获的普通局部变量被正确 release。

---

### 4. 场景与流程

#### 4.1 主成功场景
1. **业务：** `Mono.defer` 中 `token = FlatExecutionEngine.tryAcquire(RESOURCE_ID)`（无 ThreadLocal 绑定）。
2. **业务：** `token < 0` → `Mono.error(对应异常)`。
3. **业务：** `doRemoteCall()` 返回的 Mono 上挂 `.doOnSuccess(res -> release(ID, token, true))`、`.doOnError(err -> release(ID, token, false))`。
4. **系统：** release 从 token 解出 version+bucketIdx，与执行它的 Reactor 线程无关，正确回滚并发段、上报 EWMA。（Use BR-060, BR-061）

#### 4.2 异常流程
* **4a. acquire 与 release 不同线程：** 这是本用例的常态而非异常——token 自描述保证正确性（区别于 Sentinel 依赖 ThreadLocal 的历史包袱）。

#### 非功能性需求
* **零 ThreadLocal：** 生命周期不依赖线程本地存储。
* **零内存泄漏：** token 是基本类型 long，闭包捕获不持有任何可泄漏资源。

---

### 5. 其他要求
* **关键业务规则：** BR-060-thread-agnostic-release、BR-061-token-closure（见 `rules.md`）
* **模块定位：** v1 提供独立 `reactive-adapter` 模块（Reactor 操作符/便捷包装），核心库本身即 reactive-safe。
