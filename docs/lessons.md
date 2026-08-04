# 经验教训（Lessons Learned）

> 来源：2026-08-02 对 circuit-breaker 的"第一性原理 + 对抗性"review → 反思 → 实施（P0+P1+P2）全过程。
> 记录**可复用的、具体的**教训，不写"要仔细"这类废话。每条都锚定真实事件。

---

## 1. 不读代码的 review 没有公信力

**事件**：首轮 review 提出 13 条，其中 **3 条完全错误**（C1/C4/M1），而**真正的 P0 缺陷（N1 令牌桶 1 秒粒度补充）首轮完全漏掉**。

- C1 把"41 位时间戳 69 年环绕"当成 critical，实则 RT 用模减法 `(now−token_time) & TIME_MASK`，对任何 RT<2^41ms 恒正确。
- C4 称"时钟回退会令牌桶瞬间填满"，实则 `nTok<1` 直接 `return false`，根本不写回。
- **M1 甚至自承「// 未看到源码，但从文档推断」**——竞态不存在，整个方法体 `synchronized` + `AtomicReferenceArray.set` 安全发布。

**教训**：对着设计文档臆测实现行为 = 编故事。**任何"机制级"断言（"会溢出""会填满""有竞态"）必须引用 `文件:行` 的真实代码**。读代码之前，review 的产出至多是"待验证的怀疑清单"，不是"发现"。

---

## 2. 对抗性自审比原始对抗性 review 更有价值

**事件**：用户用 `/goal` 要求"反思首轮 review 建议是否合理"。正是这个"质疑我自己产出"的指令，迫使我读真实源码逐条证伪——**这才发现了漏掉的 N1**。

**教训**：对别人代码的 adversarial review 容易"找疑点"（且部分是错的）；**对自己的 review 结论再做一轮 adversarial 复核（"我凭什么这么断言？代码真的这样吗？"）才是把水分挤干的关键一步**。把"证伪自己"设为 review 流程的强制环节。

---

## 3. 测试通过 ≠ 行为正确：警惕"不区分对错"的测试

**事件**：令牌桶的全部既有测试（`LazyTokenBucketTest`/`LowTpsTokenBucketTest`/`TpsDynamicsTokenBucketTest`）都用 **qps=1 或 qps=1000**。在这两个值下，buggy 的 `(dtMs/1000)*qps` 和正确的 `dtMs*qps/1000` 因整数截断**结果完全相同**——测试全绿，却从未覆盖缺陷。bug 只在 **qps≥1000 的亚秒补充**（如 qps=1500, dtMs=1：错误给 0、正确给 1）时显现。

**教训**：
- 测试用例的取值若恰好落在"对与错等价"的区间，是在**给自己虚假的信心**。
- 写回归测试时，必须构造一个**在旧代码下失败、新代码下通过**的用例（`subSecondRefillIsMsGranular` 用 qps=1500 即此原则）。如果一个测试无法区分修复前后，它就没在守护那个修复。
- 边界值（qps=1/1000）覆盖的是"最简情况"，往往正是 bug 躲避的地方。

---

## 4. 测试名/注释会与行为静默漂移

**事件**：C3 把 `lastUpdateMs` 从 24 位缩到 20 位后，测试 `clockReversalWithin24Bit…`（名）和注释里的 `& 0xFFFFFF = 16776716`（值）**全部失效**——但断言 `ppmAfter==0` 仍然通过，因为"巨大 dt → α=1 → 重播种"对位宽不敏感。没有任何测试失败提示这个名字和注释已错。

**教训**：测试的**行为断言**和**叙述（名/注释/推导数字）是两套东西**。位宽、掩码、阈值这类"实现常量"出现在测试注释里时，改实现必须同步改注释，否则下一个人会被误导。**断言通过不代表注释还成立。**

---

## 5. 永远问"我的修复破坏了 bug 恰好没破坏的什么？"

**事件**：N1 把 `(dtMs/1000)*qps` 改成 `dtMs*qps/1000`。原 bug 代码因"先除 1000"恰好不会 long 溢出；新代码 `dtMs*qps` 在**病态空闲（≥~250 年 × 最大 qps）下会溢出**。我不得不补一个饱和守卫 `if (dtMs >= dtSat) return cap`。

**教训**：修复一个 bug 时，做一轮**针对修复本身的对抗性审查**——"原代码（哪怕是错的）是不是恰好规避了某个边界？我的'更对'的写法会不会撞上它规避的边界？" 整数溢出、空指针、并发序是最常见的三类"修复引入的新坑"。

---

## 6. 设计意图 ≠ 代码实现：漂移本身就是发现

**事件**：设计 §4.2.2 写的是 `Tokens_add = (Time_now − Time_last) × ratePerMs`（**毫秒粒度，正确意图**），但代码 `LazyTokenBucket.java:38` 写的是 `(dtMs/1000)*qps`（**整秒粒度，错**）。设计是对的，实现偷偷漂移了。

**教训**：**同时读设计文档和代码，并主动找两者的分歧**——每一处分歧都是一个候选 finding（要么文档过时，要么代码有 bug）。本轮 N1 正是"设计说 ms、代码做秒"这道裂缝里掉出来的。不要假设"代码忠实实现了设计"。

---

## 7. 架构守卫测试是承重的

**事件**：N4 我给 `GovernanceException.fillInStackTrace()` 加了 `synchronized`（照抄 `Throwable` 签名），`HotPathGuardTest` 立刻红了——它用 ArchUnit 强制"除 `ResourceManager` 外不得有 synchronized 方法"。去掉 `synchronized`（no-op 无需同步）即过。

**教训**：这类"不变量守卫"（无 Math.exp、无 synchronized 热路径、零分配）把**架构约束变成了可执行的失败**，比注释/口头约定可靠得多。新建项目时应尽早把核心不变量（CLAUDE.md 里那些"invariant"）落成守卫测试。

---

## 8. 规范性常量的改动必须做"全仓扫描式"同步，不能定点修

**事件**：version 6→10、generation 4→8 这两个位宽变更，涟漪到 **~10 处文档**：design.md（表格/位移图/encode 代码/§4.2/§4.3/§6.4 多处）、04/05/07 系统文档、domain 的 data-model artifact、usecase rules、analyze-code-output。CLAUDE.md 明确"位偏移、阻断码、状态机边界是 normative"。定点编辑遗漏了 04_DATA_MODEL 第 40 行的深表和多个 domain artifact，**靠最后一次 `grep` 扫描才捞干净**。

**教训**：对"normative 常量"（位宽、掩码、阻断码、偏移）的任何改动，**最后一步必须是跨全仓的 grep 扫描**（`grep -rnE "旧值"`），而不是依赖记忆中"我改过的地方"。定点编辑必然有漏。

---

## 9. 精确的正则批量替换是合理的，但要可验证

**事件**：N2 删 `register` 的 `name` 参数，~40 处调用方用一条 perl 正则完成：
`perl -i -pe 's/ResourceManager\.register\(\s*"[^"]*"\s*,/ResourceManager.register(/g'`
安全的原因：正则要求**第一个参数是字符串字面量**，因此不会误伤 `CircuitBreakerCollector.register(registry, …)`。改完立刻 `grep` 验证残留 + 跑全量测试。

**教训**：当专用工具无法做多文件正则替换时，**精确（窄）的正则 + grep 验证 + 构建兜底**是正确做法——比 40 次手工 Edit 更少出错。关键是正则要"窄到只匹配目标"，且**必须有用例能捕获误伤**（这里是编译/测试）。

---

## 10. 工具使用：Edit 的 old_string 边界要慎选

**事件**：本会话两次 Edit 失误——想"在方法前插入新测试"，却把 `old_string` 写成了被插入方法的开头几行，结果**把整个方法体删掉了**（只剩签名）。

**教训**：
- 想**插入**时，`old_string` 用一个**唯一的锚点行**（如紧邻的空行 + 下一方法签名），`new_string` = 锚点 + 新内容，**不要把要保留的方法签名包进 old_string**。
- 想**替换整个方法**时，old_string 必须从签名到闭合 `}` 完整。
- 混淆这两种意图 = 丢代码。每次 Edit 前默念"我是插入还是替换？old_string 边界落在哪？"

---

## 一句话总结

> **读代码之前别下结论；质疑自己比质疑别人更出活；测试要能区分对错；改 normative 常量必须全仓 grep；每次修复都再问一遍"我新破了什么"。**

---

## 11. 工作树被外部进程改写：把"文件完整性"当一等公民

**事件**（2026-08-04 e2e 会话）：开工时发现全部 60 个 `src/**/*.java` 变成二进制垃圾（`%TSD-Header-###%` 头 + 12KB blob），`git checkout --` 无法恢复（环境问题），只能用 `git show HEAD:<path> > <path>` 逐文件恢复。更诡异的是**恢复后几分钟文件会再次变坏**——并发进程（`wrdlv4.exe`/`ztsmtbsclient.exe`，疑似 DLP/同步 agent）持续改写工作树，且只命中它碰过的 4 个文件；本会话自己写的 `.java` 从未被污染。

**教训**：
- **跑测试/提交前必须做完整性扫描**：`for f in $(git ls-files 'src/**/*.java'); do head -c 20 "$f" | grep -q "TSD-Header" && echo "CORRUPT: $f"; done`。`file` 命令在此环境不可靠（曾报"0 个有效源文件"而编译却通过），用头字节判据。
- **`git checkout --` 失败时换 `git show HEAD:path > path`**——同是"从 HEAD 恢复"，后者在此环境可用。
- 恢复会丢失未提交的合法工作；**恢复前先 `git diff` 存档**，恢复后逐文件核对哪些是并发 agent 的合法修改（如 `EwmaCircuitBreaker` 的 EWMA re-seed），保留正确的、只丢弃损坏的。
- 对"会被外部改写"的树，**信任每一次 `read` 的快照标签，编辑前重读**；多个 agent 同时写同一文件时，编辑工具的回显可能与磁盘不符，必须回读验证。

---

## 12. review 报告说"已修复"≠ 代码里真的修了

**事件**：`CODE_REVIEW_AA_COMPREHENSIVE.md` 声称多个缺陷"已修复"（Outcome 三态、SegmentedConcurrency 悲观预检 + 下溢守卫、EWMA 长空闲 re-seed），但逐一对照 `git diff` 后发现：**Outcome.java 是损坏的二进制、从未被任何代码引用**（`CircuitBreakerOperator.wrap` 仍用 `boolean success`），SegmentedConcurrency 只有 69 行、review 引用的"49-67/80-91 行"根本不存在；`ConfigSwapper` 的校验确实在（上一个 session 的未提交工作）。三个"已修复"里只有一个是真的。

**教训**：
- review 报告的**状态字段（"已修复/未修复"）是叙述，不是事实**。判定标准只有一条：`git diff` + 运行中的测试。引用"文件:行"的 claim，先验证那行真的存在。
- 更狠的一招：**编译级验证**——report 说 `Outcome` 已接入，但 `grep` 全仓 `Outcome.` 零引用，编译也证明它没被接线。用"能否编译 + 行为测试是否覆盖"来戳穿叙述性状态。
- 交接时报告一个"乐观状态"是人类常态（尤其 AI 生成的交接文档），**接手方必须假设所有"已修复"都需要复核**，成本远低于信任一个错的 claim。

---

## 13. 防御性守卫会创造自己的反面：EWMA re-seed 的 8τ 退化

**事件**：并发 agent 给 `EwmaCircuitBreaker.updateEwma` 加"长空闲 re-seed"守卫：`(dtMs >> 3) >= cfg.ewmaTauMs` 时重置 count=1，防止多小时后一条过期失败误跳闸。这个相对阈值在 **τ=1ms 时完全退化**——任何 8ms+ 的请求间隔（普通流量！）都触发 re-seed，`minCalls` 永远凑不齐，`ResourceIsolationTest` 两个用例当场红了（`expected -2 but was <token>`：断路器面对持续失败流量永不跳闸）。

**教训**：
- **相对阈值必须有绝对下限**。`>8τ` 只表达"相对 EWMA 窗口的空闲"，对微小 τ 是物理上荒谬的"空闲"；最终守卫是 `dtMs ≥ 8τ && dtMs ≥ 100ms` 双条件。
- **给"防御性守卫"写测试时，先问它会不会把正常流量误伤成攻击流量**。re-seed 的本意是防"过期失败误跳闸"，副作用却是"稀疏持续失败永不跳闸"——两种症状在同一行代码里。
- 修改共享热路径（`updateEwma`）后**必须跑全量**，不能只跑新增测试：`ResourceIsolationTest` 用的是 τ=1ms 的"快反应"配置，恰好踩中守卫退化面，而新写的 re-seed 测试（τ=1000ms）永远测不出来。
- 并发 agent 的"修复"同样要过这套审查——**不是自己的 diff 也不能免检**。

---

## 14. 修复的"验证力"与测试的"防护力"都要量化

**事件**：会话早期观察到两个 flaky 测试：`FaultInjectionTest.concurrencySaturationFault`（limit<SEG=16 → limitPerSeg=1，释放 3 个槽中 1 个后裸 `tryAcquire` 撞上被占 segment，P≈2/16≈12.5% 假失败）和 `FlatExecutionEngineTest.concurrencyBlocksAndReleasesViaEngine`（P≈1/16≈6.25%）。修复：把"裸 assert 单次 acquire"改成复用已有的 `acquireOrFail` 重试 helper。

**教训**：
- **flaky 修复必须能量化验证**：两次修复分别有 ~12.5% 和 ~6.25% 的旧失败率，连续 3 轮全量跑（244×3=732 次测试）0 失败——如果旧 bug 仍在，3 轮全绿的联合概率仅 ~66%，这本身就是证据强度。
- **重试 helper 改测试而不是放宽断言**：测试意图是"释放腾出槽位"，`acquireOrFail` 保留意图、消除随机性；放宽为 `isGreaterThanOrEqualTo(0)` 会吞掉真实回归。
- **审计同类模式时量化风险**：其余 `isGreaterThanOrEqualTo(0)` 的 acquire assert 逐一核对——limit=1_000_000（segment 永不饱和）或全量释放（segment 全空）才安全，否则同样有概率性假失败。

---

## 15. 静态可观测性：测试直接断言"发布可见性"字段类型

**事件**：Defect 3 把 `ResourceManager.STATES` 从普通数组改成 `AtomicReferenceArray`——`register()` 里非 volatile 写 `STATES[id]=st` 可能被消费者线程晚观察（其后的 `CONFIGS.set` 是 volatile，只排序其后的访问），导致刚注册的 id 报"unregistered"。

**教训**：
- **发布/消费共享状态时，写序必须保证"状态先于配置可见"**：`STATES.set(id, st)` 在 `CONFIGS.set(id, config)` 之前（两者都 volatile），消费者先读 CONFIGS 命中、后读 STATES 必命中。
- 这类可见性修复**无法用行为测试直接证明**（需要真实内存序竞争），但可以被"类型即契约"测试守护：断言 `STATES` 是 `AtomicReferenceArray` 实例——把"字段类型"变成可断言的架构不变量，与 lesson 7 的守卫测试同族。
- 迁移 `STATES[...]` → `STATES.get(...)` 必须全仓 grep 调用点（lesson 8 同款），`FlatExecutionEngine` 三处、`ResourceManager` 两处都是这么漏不掉、也改不错的。

---

## 一句话总结（2026-08-04 增补）

> **文件完整性优先于一切；review 的"已修复"要过 git diff 复核；防御性守卫要给相对阈值加绝对下限；flaky 修复用联合概率量化证据；共享状态的发布可见性用"类型即契约"守护。**
