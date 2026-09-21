# Feature Specification: Story #008 react-max-steps

**Feature Branch**: `story-008-react-max-steps`
**Created**: 2026-09-21
**Status**: Draft
**Input**: User description: "Story #008 react-max-steps — `LinearTurnEngine` ReAct loop 上限守卫 + `MaxStepsExceeded(maxSteps, totalUsage)` 事件发射 + AC-07 黑盒验证(AC-07, dsh §0.4 L127-131 + §1.5.1 ReAct 守卫 + §6.1 L3619-3621)"

**Source Design Doc**: `dsh_agent_design.md` v1.5.34
- §0.4 AC-07 L127-131(ReAct 上限 — `agent.react.max-steps: 3` + LLM mock 每次只返 tool call → 第 3 步后发 `MaxStepsExceeded(3, totalUsage=...)` + turn 正常 `done()` + 不无限循环)
- §1.5.3 L6537(2026-09-03 ReAct 语义显式化 + maxSteps 守卫—— 已合入 3 个 `AgentEvent` 子类:`ReasoningStarted` / `ObservationAppended` / `MaxStepsExceeded`)
- §6.1 L3611-3629(LinearTurnEngine step 守卫代码:`int maxSteps = ctx.config().getReactMaxSteps()` + for-loop `step <= maxSteps` + `if (step > maxSteps) sink.onNext(MaxStepsExceeded(...))`)
- §6.1 L3522-3525 / 3539-3541(mermaid 时序图显式「opt hit maxSteps cap → MaxStepsExceeded then markDone」)
- §8.1 `agent.react.max-steps` 字段(default 50, 0 = 不限)
- §15 ErrorCode(本 Story **0 新增**;`MaxStepsExceeded` 是结构化事件不是异常,失败通过 `LINGS-C02` 配置校验复用)
- §16 Glossary(ReAct Loop / MaxStepsExceeded 事件)
- §17 Risk Register(本 Story 缓解 R-04 ReAct 失控循环:设置上限 + 结构化事件;非新增 R-XX)

**Constitution**: `.specify/memory/constitution.md` v1.0
- §1 #4 Compactor v1(语义摘要留 v2 —— **不**涉及,本 Story 仅截断 ReAct 循环)
- §1 #7 编排可扩展(`FlowEngine` 接口 + `LinearTurnEngine` 默认实现 — 本 Story 修改默认实现不引入新接口契约)
- §1 #11 默认实现位置(lingshu-core 内置 + 按需加载 — 本 Story 修改 `LinearTurnEngine` 默认实现)
- §1 #12 启动时配置校验(`AgentFactory.create()` 已有 `getReactMaxSteps() <= 0` 校验 L233-235,本 Story 复用)
- §2 13 项依赖锁定(R-13 mitigation (d) dep-tree 自查,**0 新增** — 全部 JDK 8 内置)
- §3 NFR baseline:`reactMaxSteps` 配置 0 = 不限(默认 50);AC-07 P99 ≤ maxSteps + toolTimeoutSec × maxSteps(保护预算上界)
- §4 错误码约定:复用 `LINGS-C02/C03`,本 Story **0 新增** ErrorCode
- §5 7 层金字塔:L1 Unit(`LinearTurnEngine` max-steps guard)+ L1 Unit(`AgentEvent.MaxStepsExceeded` 字段 / `StopReason` 仍为 `END_TURN`)+ L2 Slice(AC-07 黑盒)
- §10 R-04 ReAct 失控循环缓解:本 Story 落实 (a) for-loop 上限 + (b) `MaxStepsExceeded` 结构化事件 + (c) turn 正常 `done()`(StopReason=END_TURN)

**对应 AC**: **AC-07**(ReAct 上限 §0.4 L127-131)—— yml `agent.react.max-steps: 3` + LLM mock 每次只返 tool call → 第 3 步后发 `MaxStepsExceeded(3, totalUsage=...)` 事件 + turn 正常 `done()`(StopReason=END_TURN)+ **不**无限循环;§1.5.3 ReAct 守卫验证 + §6.1 L3619-3621 step > maxSteps guard。

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — MaxStepsExceeded 事件在 step == maxSteps 时正确发射 (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我**期望** 当 Agent 陷入 tool-call 死循环(LLM 永远返 tool call 不返 final answer)且 yml 配置 `agent.react.max-steps: 3`,Agent 在第 3 步执行完成后**不**无限循环,而是发 `MaxStepsExceeded(3, totalUsage=...)` 事件告知我「已到上限被迫终止」,然后 turn 正常 `done()`(StopReason=END_TURN)。这样我可以:(1) 在 UI / 日志里看到这个事件触发告警;(2) 知道是配置上限问题不是 bug;(3) 通过调整 `agent.react.max-steps` 缓解。

**Why this priority**: 这是 **AC-07 的核心机制**。当前(Story #001—#007 已 merged)`LinearTurnEngine.runTurn` 有 `for (int step = 1; step <= maxSteps; step++)` 上限,**但** §6.1 L3619-3621 设计的 `if (step > maxSteps) sink.onNext(new AgentEvent.MaxStepsExceeded(...))` **永远不会触发**(for-loop bound 使 step 永远 ≤ maxSteps)—— **bug**:事件定义(`MaxStepsExceeded` 在 `AgentEvent.java` L107-110)已存在,但**无任何发射路径**;turn 在 loop 结束时直接走 L177-180 `TurnCompleted(END_TURN, totalUsage)` 了事,**用户**和**运维**都无法区分「自然 END_TURN」与「被 maxSteps 强杀」两种情形 —— AC-07 在 design 层面满足契约但**代码实际 fail**。

**Independent Test**: 在 `lingshu-core/src/test/.../flow/MaxStepsGuardTest`(L1 Unit,不启 Spring)写核心用例 —— 配置 `reactMaxSteps=3` + 脚本化 LLM 每次只返 tool call + 用 `CapturingSubscriber` 捕获事件序列;断言:(a) 总共 3 次 `ReasoningStarted`(step=1,2,3);(b) 第 3 步 ObservationAppended 之后立即发 `MaxStepsExceeded(3, totalUsage=...)`;(c) 紧跟 `TurnCompleted(END_TURN, totalUsage)`;(d) `totalUsage` 等于 3 次 LLM 调用的 Usage 累加;(e) 没有任何 step=4 的 `ReasoningStarted`。

**Acceptance Scenarios**:

1. **Given** yml `agent.react.max-steps: 3` + LLM mock 每次只返 tool call(永远不 END_TURN)
   **When** Agent 跑 turn
   **Then** 顺序发射 3 次 `ReasoningStarted(1, 3)` / `(2, 3)` / `(3, 3)`,3 次 `ToolCompleted` + `ObservationAppended`
   **And** 第 3 步 ObservationAppended 之后立即 `MaxStepsExceeded(3, totalUsage=Usage(3 calls))`
   **And** 紧跟 `TurnCompleted(END_TURN, totalUsage=Usage(3 calls))`(**不**抛异常 + **不**走 ERROR 分支)
   **And** 总事件数 = `3 × (ReasoningStarted + ToolCompleted + ObservationAppended) + 1 MaxStepsExceeded + 1 TurnCompleted = 11 个`(**不**多不少)

2. **Given** yml `agent.react.max-steps: 5` + LLM mock 第 1-3 步返 tool call + 第 4 步 END_TURN
   **When** Agent 跑 turn
   **Then** 只发射 3 次 `ReasoningStarted(1..3)` + 3 次 `ObservationAppended` + 1 `TurnCompleted(END_TURN)`
   **And** **不**发射 `MaxStepsExceeded`(自然 END_TURN,未触上限)
   **And** 不发射 step=4 / 5 的 ReasoningStarted(提前结束,不再回 loop head)

3. **Given** yml `agent.react.max-steps: 1` + LLM mock 返 tool call
   **When** Agent 跑 turn
   **Then** 只发射 1 次 `ReasoningStarted(1, 1)` + 1 `ToolCompleted` + 1 `ObservationAppended` + 1 `MaxStepsExceeded(1, totalUsage)` + 1 `TurnCompleted(END_TURN)`
   **And** 没有 step=2 / 3...(零步观察)

4. **Given** yml `agent.react.max-steps: 0`(0 = 不限)+ LLM mock 每次只返 tool call
   **When** Agent 跑 turn(测试 10 步提前 break — 否则会真死循环)
   **Then** 因 `AgentFactory.create()` 启动期校验 `getReactMaxSteps() <= 0` 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED`(`L233-235`)
   **And** turn 不会进入 engine(避免运行时死循环)

---

### User Story 2 — max-steps guard 在工具调用 / LLM 调用抛异常时仍生效 (Priority: P1)

作为 **Charlie(框架贡献者)**,我**期望** 即使 turn 中途某次 tool 调用抛异常 / LLM 调用超时,ReAct 上限守卫仍**最终**触发 —— 不会因为异常路径绕过 step 计数导致 turn 失控。这样异常路径与正常路径在「最终总会 done」这个契约上是对称的。

**Why this priority**: 这是 **AC-07 的鲁棒性边界**。`LinearTurnEngine.runTurn` L184-188 catch RuntimeException 兜底发 `ErrorEvent` + `TurnCompleted(ERROR)`,**但**这个分支不会发 `MaxStepsExceeded`(异常与上限是两件事);问题是如果异常发生在 for-loop body 内、且异常之后 for-loop 又因 step > maxSteps 自然结束,`MaxStepsExceeded` 会被吞 —— 必须保证「for-loop 完整跑完 + 自然结束 + last 仍有 toolCalls」三个条件**独立**于异常路径。

**Independent Test**: 加测试 —— 配置 `reactMaxSteps=2` + 脚本化 LLM:第 1 步 END_TURN(异常路径测试用),第 2 步返 tool call(触发上限);验证 `MaxStepsExceeded(2, totalUsage)` 与 `TurnCompleted(END_TURN)` 顺序。

**Acceptance Scenarios**:

1. **Given** yml `agent.react.max-steps: 2` + LLM mock 第 1 步抛 RuntimeException
   **When** Agent 跑 turn
   **Then** catch 分支发 `ErrorEvent` + `TurnCompleted(ERROR)`,**不**发 `MaxStepsExceeded`(未触上限前就异常)
   **And** totalUsage = 第 1 步累加(部分 Usage,error 路径仍记账)

2. **Given** yml `agent.react.max-steps: 3` + LLM mock:第 1 步 tool call(成功)+ 第 2 步 tool call(tool 抛 RuntimeException → ToolResult.error)+ 第 3 步 tool call
   **When** Agent 跑 turn
   **Then** 第 3 步 ObservationAppended 之后发 `MaxStepsExceeded(3, totalUsage=Usage(3 calls))`(正常触上限)
   **And** 第 2 步 tool 异常**不影响** step 计数(异常已翻译为 ToolResult.error 进 history,不抛回 engine)

---

### User Story 3 — AgentEvent.MaxStepsExceeded 字段 / StopReason 契约 (Priority: P2)

作为 **Charlie(框架贡献者)**,我**期望** `AgentEvent.MaxStepsExceeded` 携带完整诊断信息(maxSteps + totalUsage),`TurnCompleted` 紧随其后,StopReason 仍为 `END_TURN`(不是新加 `MAX_STEPS`)—— 这样 (a) `MaxStepsExceeded` 是「诊断信号」,`TurnCompleted` 是「终止语义」,两者正交;(b) 不需要改 `StopReason` enum,避免破坏 Story #005 的 cancellation 状态机 + Story #010 的 OTel metric(都依赖 `StopReason`)。

**Why this priority**: 这是 **AC-07 的接口契约**。`AgentEvent.MaxStepsExceeded` 类定义在 `AgentEvent.java` L107-110 已存在,字段 `maxSteps` + `totalUsage` 已固化;`StopReason` enum 在 `StopReason.java` L8-21 已固化(END_TURN / TOOL_USE / MAX_TOKENS / COMPACTED / CANCELLED / ERROR)。本 Story **不**新增 `StopReason.MAX_STEPS`,**不**改 `MaxStepsExceeded` 字段,**仅**在 `LinearTurnEngine` 找到正确的发射点 + 顺序:MaxStepsExceeded → TurnCompleted(END_TURN)。

**Independent Test**: `MaxStepsExceededTest`(L1 Unit)+ 反射验证类字段 + `StopReasonTest`(回归)枚举值不变。

**Acceptance Scenarios**:

1. **Given** `AgentEvent.MaxStepsExceeded` 类定义
   **When** 反射读 `getMaxSteps()` / `getTotalUsage()` getter
   **Then** 字段类型 `int` / `Usage`,Lombok `@Getter` 生成(`AgentEvent.java` L107-110 不变)

2. **Given** `StopReason` enum
   **When** 枚举所有 value
   **Then** 仅含 `END_TURN / TOOL_USE / MAX_TOKENS / COMPACTED / CANCELLED / ERROR` 6 值,**无** `MAX_STEPS`(本 Story 0 新增)

3. **Given** max-steps 触发的 turn
   **When** 检查事件序列末 2 个事件
   **Then** `MaxStepsExceeded` 在前,`TurnCompleted(reason=END_TURN)` 在后(顺序固定)
   **And** `TurnCompleted.usage` == `MaxStepsExceeded.totalUsage`(同一 Usage 引用,非拷贝)

---

### Edge Cases

- **EC-1**: `reactMaxSteps=0` — `AgentFactory.create()` 启动期校验抛 `LINGS-C02`,engine 不会被调用(US1 AS4)
- **EC-2**: `reactMaxSteps` 负数 — 启动期校验抛 `LINGS-C02`(< 0 也 reject)
- **EC-3**: maxSteps 极小(1)+ LLM 返 tool call — 第 1 步 ObservationAppended 之后立即 `MaxStepsExceeded(1, ...)`,不再回 loop head(US1 AS3)
- **EC-4**: maxSteps 极大(1000)+ LLM 总是 tool call — 测试只跑 N 步后断言事件序列有 N 个 `ReasoningStarted` + 1 个 `MaxStepsExceeded(1000)`,不真跑 1000 步(性能保护)
- **EC-5**: turn 中途 cancellation 触发 + 未到 maxSteps — `TurnCompleted(CANCELLED)`,**不**发 `MaxStepsExceeded`(cancellation 优先)
- **EC-6**: turn 中途 ctx.done() / 程序化 markDone — break 出 loop,`TurnCompleted(last.getStopReason())`,**不**发 `MaxStepsExceeded`(done() 优先于上限)
- **EC-7**: for-loop 末次迭代(step == maxSteps)+ LLM 返 no-tool-call — 走 L162-165 break,**不**发 `MaxStepsExceeded`(自然 END_TURN,未触上限)
- **EC-8**: LLM 流抛出 exception 在 step == maxSteps — catch 发 `ErrorEvent` + `TurnCompleted(ERROR)`,**不**发 `MaxStepsExceeded`(异常优先)
- **EC-9**: 在飞 turn(`registry.publish` 触发 cfg 切换)未到 maxSteps — 旧 turn 冻结 `reactMaxSteps` 值,行为不变(Story #007 冻结语义自然兼容)

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `LinearTurnEngine.runTurn` 必须在 for-loop 因 `step > maxSteps` 自然结束时,发射 `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` 事件,**然后**再发射 `TurnCompleted(END_TURN, totalUsage)`。
- **FR-002**: `MaxStepsExceeded.totalUsage` 必须**等于**该 turn 实际累计的 `Usage`(input + output token 之和),与 `TurnCompleted.usage` 同一引用(`Usage` 是 `@Value` 不可变,引用语义 = 值语义)。
- **FR-003**: `MaxStepsExceeded` 仅在「for-loop 自然结束 + 最后一次 LLM 响应仍含 tool calls」两个条件**同时**满足时发射;其他终止路径(break 无 tool calls / cancellation / done() / exception)**不**发射。
- **FR-004**: `TurnCompleted` 的 `StopReason` 在 max-steps 触发场景下**仍为 `END_TURN`**(不引入新 `StopReason` 值,保持向后兼容 Story #005 cancellation 状态机 / Story #010 OTel metric)。
- **FR-005**: `MaxStepsExceeded` 事件发射顺序**必须**早于 `TurnCompleted`,二者之间**不**插入任何其他事件。
- **FR-006**: `AgentFactory.create()` 启动期校验 `config.getReactMaxSteps() <= 0` 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED`(`L233-235` 已存在),**不**在本 Story 范围内改 —— 复用 Story #001 既有路径。
- **FR-007**: `AgentEvent.MaxStepsExceeded` 类定义、字段、Lombok `@Getter` **不**改(`AgentEvent.java` L107-110);`StopReason` enum **不**改(`StopReason.java` L8-21)。
- **FR-008**: 本 Story **0 新增** Maven 依赖 —— 全部 JDK 8 内置(`for-loop` / `if` / `sink.onNext` / `@Getter`)。
- **FR-009**: 本 Story **0 新增** ErrorCode —— `LINGS-C02` 复用。
- **FR-010**: 本 Story **0 新增** `Slot` SPI —— 修改 `LinearTurnEngine`(默认实现,§1 #11),不引入新接口契约(§1 #7 编排可扩展,不破坏)。

### Key Entities

- `AgentEvent.MaxStepsExceeded`(`AgentEvent.java` L107-110)—— 已存在,字段 `int maxSteps` + `Usage totalUsage`,Lombok `@Getter`,**不**改
- `StopReason`(`StopReason.java` L8-21)—— 已存在 enum,**不**改,本 Story 复用 `END_TURN`
- `LinearTurnEngine.runTurn`(`LinearTurnEngine.java` L86-189)—— 修改:在 for-loop 自然结束分支(L177-180)前置 `MaxStepsExceeded` 发射,**前提**是 `last != null && last.getToolCalls() != null && !last.getToolCalls().isEmpty()`

### Non-Functional Requirements

- **NFR-001**: AC-07 单 turn `MaxStepsExceeded` 触发 P99 latency ≤ `maxSteps × (toolTimeoutSec + llmTimeoutSec)`(已固化边界,本 Story 不引入额外开销)
- **NFR-002**: `MaxStepsExceeded` 发射路径**不**新增内存分配 —— 直接引用既有 `totalUsage`(`Usage` `@Value` 不可变),非 snapshot 拷贝
- **NFR-003**: 单元测试覆盖率 `LinearTurnEngine.runTurn` 终止分支 ≥ 80%(US1 4 AS + US2 2 AS + US3 3 AS = 9 AS,覆盖 5 个终止路径)
- **NFR-004**: JDK 8 兼容 —— 不用 `var` / `record` / `List.of` / `sealed`(`for (int step = 1; step <= maxSteps; step++)` + `if` + `sink.onNext` 全部 JDK 8 内置)
- **NFR-005**: 0 新增 Maven 依赖 —— `mvn dependency:tree` diff 前后完全一致(R-13 mitigation (d) 强制项)

---

## Success Criteria

- ✅ AC-07 黑盒通过:`mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest` 9 个 case 全绿
- ✅ for-loop 自然结束 + last 含 tool calls → `MaxStepsExceeded` 在 `TurnCompleted` 之前发射
- ✅ for-loop 自然结束 + last 不含 tool calls → 仅 `TurnCompleted(END_TURN)`,**不**发 `MaxStepsExceeded`
- ✅ cancellation / done() / exception 路径**不**发 `MaxStepsExceeded`
- ✅ `AgentEvent.MaxStepsExceeded` 类 / `StopReason` enum 字段不变(向后兼容)
- ✅ `mvn dependency:tree` diff = 0 新增依赖(R-13 mitigation (d))
- ✅ `mvn -pl lingshu-core test` 全绿(Story #001—#008 全部测试,无 regression)

---

## Open Questions

- (无 — AC-07 + dsh §1.5.3 + §6.1 三个来源完全锁定,Story 边界清晰)