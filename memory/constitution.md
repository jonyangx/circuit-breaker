# Circuit Breaker Constitution

<!-- 纳秒级、零分配、无锁化的流量治理（熔断 / 限流 / 并发控制）组件库。
     对标 Alibaba Sentinel / Hystrix 的核心能力，以极致性能为第一性原理。 -->

[Always respond in 中文]

## Core Principles

### 0. Project Type Detection (MUST EXECUTE FIRST)

**本项目已检测类型：Java / JVM（目标 Java 21+）。**

```bash
IS_JAVA=$( [ -f "pom.xml" ] || [ -f "build.gradle" ] || [ -f "build.gradle.kts" ] && echo true )
IS_MUMBLE=$( grep -q "mumble-sdk" pom.xml build.gradle 2>/dev/null || \
             grep -rq "MumbleAbstractBaseController\|AbstractSimpleDAO\|@MumbleMessageService" src/ 2>/dev/null && echo true )
```

| 结果 | 处理 |
|------|------|
| `IS_MUMBLE=true` | **优先调用 `mumblesdk` skill**，所有设计/代码/框架遵循 MumbleSDK 规范 |
| `IS_JAVA=true` | 使用通用 Java 规范 |
| 其他 | 使用对应语言规范 |

> 注：本项目在创建本宪章时尚未生成构建文件（pom.xml/build.gradle），故脚本层面返回未初始化；
> 但 `docs/brd/design.md` 明确目标为 JVM（`AtomicLong`/`LongAdder`/`ThreadLocalRandom`/虚拟线程/`@Contended`），技术栈判定为 **Java 21+**，构建脚手架（Gradle Kotlin DSL）在 Phase 3 生成。

### I. Library-First

本组件是**嵌入式流量治理库（embedded SDK）**，非应用：

- 库 MUST 自包含、可独立测试、有文档；通过 `FlatExecutionEngine.tryAcquire()` / `release()` Java API 接入业务
- 不依赖业务容器即可运行（可被网关、Service Mesh Sidecar、连接池、RPC 框架内嵌）
- 仓库定位为单一核心库 + JMH 基准模块，禁止为"组织结构"而拆分无独立用途的模块

### II. CLI Interface — 【本项目豁免】

> ease-spec 默认要求"每个库暴露 text in/out CLI"。**本项目声明豁免该原则**：
> 熔断/限流组件的语义是进程内流量拦截，仅通过 Java API 被业务调用，不存在 stdin/stdout 式 CLI 语义。
> 若未来需要规则注入或压测驱动入口，以**可选 demo/REPL 模块**形式提供，不作为核心库的强制契约。

### III. Test-First (NON-NEGOTIABLE)

- TDD 强制：先写测试 → 用户确认 → 测试失败 → 再实现；严格 Red-Green-Refactor
- 热路径代码（token 编解码、令牌桶、EWMA 熔断状态机、并发控制）无对应测试不得提交
- 覆盖率门槛（继承 ease-spec / flow-3 门控）：行 ≥ 80% / 分支 ≥ 70% / 方法 ≥ 85%
- **热路径变更 MUST 附带 JMH 微基准回归**，见下方"项目专属不变量"

### IV. Integration Testing

- 关注点：模块间契约（acquire ↔ release 跨线程/跨代际）、状态机迁移闭环、热更新（RCU）在途请求正确性
- 必须覆盖：Reactor/Netty 跨线程 release 场景（验证 token 自描述正确性）、配置热换在途 release 场景
- 共享 schema：64 位 token 位布局、`AtomicLong` 状态字位布局为跨模块契约，变更需契约测试

### V. Observability

- 采用轻量方案（`docs/brd/design.md` 第 7 章）：`LongAdder passCount/blockCount` 只增不清，scraper 端算差值，**禁止调用 `reset()`**
- EWMA ppm 暴露为 Prometheus Gauge，pass/block 暴露为 counter
- 观测/采集**严禁进入 acquire/release 热路径自旋**，仅 release 末尾异步递增或由低频后台线程读取

### VI. Versioning & Breaking Changes

- 语义化版本 MAJOR.MINOR.PATCH；破坏性变更（如 token 位布局、状态字位布局调整）MUST 提供迁移说明
- 维护 CHANGELOG；位布局常量（`*_BITS`/`*_SHIFT`）变更属于破坏性变更

### VII. Simplicity（性能约束下的特例说明）

- 默认遵循 YAGNI、避免过度设计；复杂度 MUST 被论证
- **本项目特例**：bit-packing（状态压缩）、lock-free CAS、lazy evaluation（惰性时间推导）、定点整数 EWMA 等复杂手段，是"纳秒级开销 / 零分配 / 无锁化"不变量的**必要代价**，而非过度设计——每一项 MUST 在代码注释或设计文档中保留显式理由，移除任一项 MUST 先证明不违反性能不变量

### VIII. Relative Paths (NON-NEGOTIABLE)

- 文件和目录的阅读、引用、生成均**必须使用项目相对路径**
- 禁止使用以 `/` 开头的绝对路径（如 `/docs/system/`）
- 正确格式：`docs/system/`、`memory/constitution.md`、`.eases/`、`docs/brd/design.md`
- 此规则适用于：文档内容、代码注释、配置文件、生成的产出物

## Project-Specific Invariants (NON-NEGOTIABLE)

> 以下六条是本项目的**宪法级**不变量，来源于 `docs/brd/design.md`。任何代码或设计变更 MUST 维持这些不变量；
> 若需变更，须走 Governance 修订流程并视为 MAJOR 版本。

1. **Performance Budget（性能预算）**：治理热路径单次调用额外开销 MUST 在**纳秒级（ns）**。任何热路径改动 MUST 通过 JMH 基准验证不劣化；引入 `Math.exp`、对象分配、锁即视为违规。
2. **Zero Heap Allocation（零堆分配）**：acquire/release 热路径 MUST 零堆分配——生命周期上下文压缩进一个 64 位 `long` token，禁止 `Entry`/`Context`/`MetricBucket` 等对象。
3. **Lock-free（无锁化）**：运行时状态更新 MUST 通过单次 `AtomicLong` CAS（竞争自旋）。热路径禁止 `synchronized`/`ReentrantLock`；可 stripe 的仅限可交换求和量（观测计数、并发近似），令牌桶与熔断状态机保留单 `AtomicLong` + `@Contended` 填充。
4. **No Governance-Side Timer Threads（治理侧无定时线程）**：状态更新 MUST 推迟到请求到来的瞬间、按时间差惰性推导（lazy evaluation）。仅观测采样与系统过载探针（第 7、10 章）可保留极低频后台线程，且**不进入请求关键路径**。
5. **Config-State Separation（配置/状态分离）**：`CONFIGS`（`ResourceConfig`，不可变、可 RCU 热换、纯参数）与 `STATES`（`ResourceState`，长生命周期、规则变更时永不重建）必须分离。**严禁把可变运行时状态耦合进可替换的 `ResourceConfig`**——这是 v1 的根因缺陷，热更新只换配置、状态跨版本稳定。
6. **Self-Describing Token（自描述 token）**：64 位 token 位布局 `[sign:1][time:41][version:6][bucketIdx:4][mask:12]` 恒定；符号位恒 0（`token<0` 即阻断，阻断码全负）。token MUST 携带 `version + bucketIdx`，使 `release()` **不依赖执行线程**——这是 Reactor/Netty 跨线程 release 正确性的根基。

## Tech & Process Constraints

- **技术栈**：Java 21+ / JVM；构建工具 **Gradle（Kotlin DSL）**；微基准 **JMH**（热路径变更必备）
- **基础坐标**：`groupId = dev.circuitbreaker`；Java 根包 `dev.circuitbreaker`
- **架构执行模型**：扁平化执行引擎（Flat Execution Engine）——整数 `resourceId` 数组寻址、bitmask 分派（`0x01` 熔断 / `0x02` 限流 / `0x04` 并发）、废除责任链与多态
- **时钟**：统一 `System.nanoTime()/1_000_000` 相对单调时间戳（启动归零 `START`），禁止 `System.currentTimeMillis()` 做治理判定
- **流程路径**：BRD 路径（`flow-1-analyze-brd` → `flow-2-design` → `flow-3-implement`），遵循 ease-spec 三阶段门控
- **文档语言策略**：设计/领域文档用中文（与 `docs/brd/design.md` 一致）；代码标识符、Javadoc、提交信息用英文（开源库惯例）

## Directory Structure (NON-NEGOTIABLE)

```
memory/
└── constitution.md              # 项目宪章（本文件）

docs/
├── brd/                         # 业务需求文档输入
│   └── design.md                # 本组件设计文档（中文）
└── domains/                     # 领域模块文档根目录
    └── [编号]-[module_name]/    # 领域模块（三位编号 + 名称）
        │                        # 例如：001-circuit-breaker
        ├── analyze-brd-output.md
        ├── analyze-code-output.md
        ├── design-output.md
        ├── artifacts/           # 工件
        ├── architecture/        # 架构设计
        ├── design/              # 详细设计
        └── usecases/            # 用例文档（从 ease-analysis 拆解，带编号）
            └── [编号]-[subdomain]/        # 子领域（三位编号）
                └── uc-[编号]-[usecase].md    # 用例（三位编号）

.eases/                          # ease-spec 规范文档（项目相对路径）
└── [编号]-[功能名]/             # 例如：001-circuit-breaker
    ├── analyze-brd/             # analyze-brd 命令产出
    │   ├── spec.md
    │   ├── plan.md
    │   ├── tasks.md
    │   └── checklists/
    ├── analyze-code/            # analyze-code 命令产出
    ├── design/                  # design 命令产出
    ├── code-implement/          # code-implement 命令产出
    ├── research.md              # 技术研究（共享）
    ├── data-model.md            # 数据模型（共享）
    └── contracts/               # 契约定义（共享）
```

## Numbering Rules (NON-NEGOTIABLE)

All directories and files MUST have a 3-digit incremental number prefix:

| Level     | Format                     | Example                        |
| --------- | -------------------------- | ------------------------------ |
| Domain    | `[编号]-[module_name]/`  | `001-circuit-breaker/`       |
| Subdomain | `[编号]-[subdomain]/`    | `001-token-bucket/`         |
| Usecase   | `uc-[编号]-[usecase].md` | `001-acquire-and-release.md` |

**Numbering Logic:**

- When adding a new domain/subdomain/usecase, scan existing directories
- Get the maximum existing number + 1
- Format as 3 digits (001, 002, 003...)

## Commands that Invoke ease-spec

Only the following commands invoke ease-spec and must complete the full lifecycle:

| Command                  | Description  | Lifecycle                                                                     |
| ------------------------ | ------------ | ----------------------------------------------------------------------------- |
| `/ease:analyze-brd`    | 分析业务需求 | 需求规范 → 技术计划 → 任务分解 → 执行实现                                         |
| `/ease:analyze-trd`    | 分析技术需求 | 需求规范 → 技术计划 → 任务分解 → 执行实现                                         |
| `/ease:analyze-code`   | 分析源代码   | 需求规范 → 技术计划 → 任务分解 → 执行实现                                         |
| `/ease:design`         | 统一设计     | 需求规范 → 需求澄清 → 技术计划 → 框架代码 → 任务分解                               |
| `/ease:code-implement` | 代码实现     | 需求规范 → 技术计划 → 任务分解 → 执行实现                                         |

### Recommended Workflows

- **Business Requirements (BRD)**: `analyze-brd` → `design` (recommended) → `code-implement`
  - BRD output focuses on Use Cases; running `design` is recommended to bridge business logic to technical design.
- **Technical Requirements (TRD)**: `analyze-trd` → `code-implement`
  - TRD output already includes Solution Design; usually skips the separate `design` step.

### Phase Content Boundaries (NON-NEGOTIABLE)

- **Phase 1 (analyze-*)**: 只产出领域文档、用例、业务规则、spec/plan/tasks。禁止生成任何代码、接口定义、DDL 脚本。
- **Phase 2 (design)**: 只产出架构设计、详细设计、框架骨架代码（方法体仅允许 `throw UnsupportedOperationException("TODO")` 占位）、测试类骨架（方法体仅允许 `fail("TODO")` 占位）。禁止在骨架中填入任何业务逻辑、分支判断、真实断言或可运行代码。
- **Phase 3 (code-implement)**: 可自由生成完整的生产代码和测试代码，但范围不得超出 Phase 2 的 tasks.md 与骨架定义的边界。
- **强制执行**：Phase 1/2 的 ease-spec implement 子阶段仅做文档层面确认和骨架完整性验证，不生成实际代码文件。

## Usecases Directory Rules (NON-NEGOTIABLE)

- Usecases MUST be extracted from ease-analysis skill
- Usecases MUST be organized by subdomain with 3-digit numbers
- Usecases MUST be split by entity with 3-digit numbers
- Usecase files MUST use kebab-case naming with number prefix

## Governance

- Constitution supersedes all other practices
- Amendments require documentation, approval, migration plan
- All PRs/reviews must verify compliance（含上述六条项目不变量与性能预算）
- Complexity must be justified（性能驱动的复杂度见原则 VII 特例说明）

## Clarifications

### Session 2026-07-30（首次创建）

- Q: 构建工具选型？ → A: **Gradle (Kotlin DSL)**（JMH 微基准为一等公民，契合纳秒级性能验证刚需）
- Q: ease-spec CLI Interface 原则是否适用？ → A: **豁免**（嵌入式治理 SDK 无 CLI 语义）
- Q: 交付物语言策略？ → A: **设计/领域文档中文，代码标识符/Javadoc/提交信息英文**
- Q: 基础 groupId / 根包？ → A: **`dev.circuitbreaker`**
- 检测到冲突：`docs/brd/design.md` 内容为纯技术设计（TRD 性质），但用户在 `/ease:starter` 中已明确选择 **BRD 路径** → 按其裁决，走 BRD 流程并据此建立宪章
- 检测到冲突：项目声明 Java 技术栈但无构建文件（未初始化）→ 构建脚手架延迟至 Phase 3 生成

**Version**: 1.0.0 | **Ratified**: 2026-07-30 | **Last Amended**: 2026-07-30
