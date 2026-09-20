# Feature Specification: Story #004 tool-parallel-dispatch

**Feature Branch**: `story-004-tool-parallel-dispatch`

**Created**: 2026-09-20

**Status**: Draft

**Input**: User description: "Story #004 tool-parallel-dispatch — LinearTurnEngine.dispatchParallel + 共享 ExecutorService + ToolExecutor + PermissionPolicy 注入 (AC-03)"

**Source Design Doc**: `dsh_agent_design.md` v1.5.34 §0.4 AC-03 L102-105 / §4.5.1 [TOOL SCHEMAS] / §4.6 Tool / ToolExecutor + 5-step pipeline / §4.7 PermissionPolicy / §4.10.1 Spring AI 边界硬规则 3 条 / §6.1 LinearTurnEngine (ReAct Loop + dispatchParallel) / §6.1 LinearTurnEngineProvider / §14.10 AuditLog (event 入审计) / §15 LINGS-T02 / T04 / T06 / T07

**Constitution**: `.specify/memory/constitution.md` v1.0 — §1 #7 编排可扩展 / §1 #11 默认实现位置 / §2 13 项依赖锁定 / §3 NFR P50/P99 性能预算 / §4 LINGS-Txx Tool 域 / §5 7 层测试金字塔(L1 Unit + L2 Slice + L3 Component + L5 E2E)/ §10 R-13 Spring AI 误用 mitigation

**对应 AC**: **AC-03**(Tool 并发加速 §0.4 L102-105)— 4 个独立 tool、每个延迟 ≈ 1s、`tool.parallelism: 4`、wall-clock ≤ 1.3s、加速比 ≥ 3.0×

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — LinearTurnEngine 真正执行 ToolCall 序列 (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我在 prompt 里同时请求 LLM 调用 4 个 tool(例如"读 4 个文件"),**期望** LinearTurnEngine 不再像 Story #001 那般硬 stop 并打 WARN 日志,而是真正调起 4 个 tool、把结果按原顺序归集到 history、继续 ReAct 循环。这样我才能让 LLM 真的"做事" —— 不只是"说话"。

**Why this priority**: 这是 Story #004 的**核心交付**(AC-03 主路径)。Story #001 demo-empty 路径故意 hard stop 是**有意设计**(见 `LinearTurnEngine.java` L98-100 注释),只为让 AC-01-1 在无 tool / 无 LLM key 的环境也能跑通;但生产环境**任何**真 prompt 都几乎一定会触发 tool call(读文件 / 跑命令 / 查 DB),**没有** ToolExecutor 注入 = LingShu = 玩具。**缺它** Story #005+ 全部阻塞(cancellation 必须穿越 tool loop / Story #008 max-steps cap 才有意义 / Story #014 session store save checkpoint 才有 history 可 save)。

**Independent Test**: 在 `lingshu-core/src/test/.../impl/flow/LinearTurnEngineToolDispatchTest` 加 1 个黑盒用例 —— 注入 `LlmProvider` mock 在第一次 LLM 调用时返 2 个 tool call、第二次返 1 个 final answer,断言 (a) history 实际收到 2 个 tool result message;(b) 调用顺序 = LLM1 → 2 tool dispatch → LLM2 → END_TURN;(c) 整个 flow 没抛 `RuntimeException`(意味着 LinearTurnEngine 顺利走完)。

**Acceptance Scenarios**:

1. **Given** LinearTurnEngine 构造时拿到 PromptBuilder + LlmProvider + ToolExecutor + PermissionPolicy 4 个依赖(SlotResolver 解析)
   **And** 1 个 `@Component ReadFileTool implements Tool` 注册到 `DefaultToolExecutor`
   **And** 1 个 `EchoLlmProvider` 在第一次 `stream()` 调用时返 `LlmResponse(toolCalls=[ToolCall(name="read_file", args={...})])`,第二次返 `LlmResponse(toolCalls=[], text="done", stopReason=END_TURN)`
   **When** `agent.runBlocking("read a.txt")` 调用
   **Then** LinearTurnEngine 顺序触发:PromptBuilder.build → LlmProvider.stream → ToolExecutor.dispatch(read_file) → LlmProvider.stream → TurnCompleted
   **And** history 收到 4 条 Message:User / Assistant(tool_calls) / ToolResult / Assistant(END_TURN)
   **And** Wall-clock 时间 < 1s(EchoLlmProvider 无延迟 + read_file 无延迟 + 单 tool 串行)
   **And** 不再打印 Story #001 那行 WARN 日志 "Story #004 wires ToolExecutor dispatch"

2. **Given** LinearTurnEngine 注入的 ToolExecutor 是 mock,`dispatch()` 抛 `ToolException.PermissionDeniedException`
   **When** LLM 返 1 个 tool call,LinearTurnEngine.runTurn 进入 Action 阶段
   **Then** 异常被捕获,**不**打断整个 turn
   **And** history 收到 1 条 `ToolResult(status=ERROR, content="Permission denied: ...")`(Story #004 把 ToolException 翻译为 ToolResult.error,与 dsh §4.10.1 硬规则 2 对齐)
   **And** Loop 继续下一轮 ReAct(step 2),让 LLM 有机会修正参数重试

3. **Given** LinearTurnEngine 注入的 ToolExecutor 是 mock,`dispatch()` 抛 `ToolException.ToolNotFoundException`
   **When** LLM 返 1 个 tool call 名字不在 registry
   **Then** history 收到 1 条 `ToolResult(status=ERROR, content="Tool not registered: <name>")`
   **And** Loop 继续,turn 不中断

---

### User Story 2 — 同 turn 多 tool call 并行 dispatch (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我希望在 prompt 里同时让 LLM 调用 N 个独立 tool(典型场景:"读 a.txt / b.txt / c.txt / d.txt" 4 个 read_file 并发),**期望** LinearTurnEngine 按 `config.toolParallelism` 并发执行,wall-clock 时间 ≤ N / parallelism × single_latency —— 而不是 N × single_latency 串行。这样生产环境 I/O 密集型 tool batch 才能真的加速。

**Why this priority**: 这是 **AC-03 的核心验证目标**(加速比 ≥ 3.0×)。dsh §6.1 LinearTurnEngine 注释明确 "3. Action: 并行 dispatch(由 ctx.config().getToolParallelism() 控制并发度)"是**默认行为**;`tool.parallelism: 1` 才退化为串行。**缺它** = LingShu 在 I/O 密集场景性能被钉死在串行,**违反** §3 NFR P50 ≤ 30s / turn 完成(10 steps)预算;Story #010 OTel 性能监控的"P99 加速"基线无从谈起。

**Independent Test**: 在 `lingshu-core/src/test/.../impl/flow/LinearTurnEngineParallelDispatchTest` 加 1 个核心黑盒用例 —— 注入 4 个 `@Component` tool 实例(每个 name 不同 / sleep 1s),注入 `EchoLlmProvider` 第一次返 4 个 tool call、第二次返 END_TURN,断言 wall-clock ≤ 1.3s(对比串行 4.0s,**加速比 ≥ 3.0×**)。

**Acceptance Scenarios**:

1. **Given** yml `agent.tool.parallelism: 4` + 4 个独立 `@Component ReadFileTool implements Tool` 注册到 DefaultToolExecutor(每个 name 不同:`read_a` / `read_b` / `read_c` / `read_d`,每个 `execute()` `Thread.sleep(1000)` 后返回 `"<name>:OK"`)
   **And** `EchoLlmProvider` 第一次 `stream()` 返 `LlmResponse(toolCalls=[4 个], text="")`,第二次返 `LlmResponse(toolCalls=[], text="done", stopReason=END_TURN)`
   **When** `agent.runBlocking("read a,b,c,d")` 调用
   **Then** Wall-clock 时间 ≤ 1.3s(对比串行基线 ≈ 4.0s,加速比 ≥ 3.0×)
   **And** history 收到 4 条 ToolResult,**顺序与 LLM 返回顺序一致**(原顺序归集,非完成顺序归集,与 dsh §14 14 决策对齐)
   **And** AgentEvent.ToolCompleted × 4 都推到 sink,**每个 tool 完成时刻不同**(并发证据)

2. **Given** yml `agent.tool.parallelism: 1`
   **When** 同 US2 Scenario 1 跑
   **Then** Wall-clock 时间 ≈ 4.0s(串行基线)
   **And** ToolCompleted 事件按 LLM 返回顺序依次推 sink

3. **Given** yml `agent.tool.parallelism: -1`(不限并发)
   **And** 8 个 tool,每个 sleep 1s
   **When** LLM 返 8 个 tool call
   **Then** Wall-clock ≈ 1.0s(全部并发)
   **And** LinearTurnEngine 内部 `Semaphore` 不创建(≤0 = 不限)

4. **Given** 4 个 tool 调用,3 个成功 + 1 个抛异常
   **When** dispatchParallel 等待全部 future
   **Then** 3 个成功结果正常写入 history(原顺序位置),1 个异常被翻译为 `ToolResult(status=ERROR, content=<ex message>)` 写入 history 原顺序位置
   **And** turn 不中断,ReAct loop 继续

---

### User Story 3 — ToolExecutor + PermissionPolicy 注入 LinearTurnEngine (Priority: P1)

作为 **Charlie(框架贡献者)**,我在升级 LinearTurnEngine 时,**期望** ToolExecutor / PermissionPolicy 通过构造器注入(不是从 TurnContext 临时拿),这样**单元测试**可以传 mock 实现,**无需**起 Spring 容器。同时保证 dsh §7.1.3 "Agent 4 个 final 字段在 T1→T4 期间不变"的不变项 —— ToolExecutor / PermissionPolicy / ToolPool 在 create() 时确定,turn 内不变。

**Why this priority**: 这是 Story #004 的**可测试性**保障。`LinearTurnEngineProvider.create()` 现在只注 PromptBuilder + LlmProvider,**缺** ToolExecutor / PermissionPolicy / ExecutorService;§6.1 dsh 设计明确 7 个字段(PromptBuilder / Compactor / LlmProvider / PermissionPolicy / ToolExecutor / SessionStore / ToolPool),Story #004 把后 3 个补齐(Compactor / SessionStore 是 Story #015 / #014 范畴)。**缺它** `LinearTurnEngineProvider.create()` 仍是 Story #001 残缺签名,新增字段需要后续 Story 再来改,**违反** CLAUDE.md §11 #4 Story 边界(单 Story ≤ 5 个核心文件改动 — 后续 Story 加字段会反复触及 `LinearTurnEngine.java` + `LinearTurnEngineProvider.java` 2 文件)。

**Independent Test**: 在 `LinearTurnEngineProvider` 加 1 个 mock test —— 构造 1 个 LinearTurnEngineProvider 注入 mock Routers + mock ExecutorService,调 `provider.create(cfg)` 拿到 LinearTurnEngine 实例,反射读 4 个 final 字段断言非 null。

**Acceptance Scenarios**:

1. **Given** Spring 容器内 ToolExecutorRouter / PermissionPolicyRouter / 默认 ExecutorService Bean 都就绪
   **When** `LinearTurnEngineProvider.create(AgentConfig cfg)` 调用
   **Then** 拿到 1 个 `LinearTurnEngine` 实例,其 4 个 final 字段(promptBuilder / llmProvider / toolExecutor / permissionPolicy / toolPool)全部非 null
   **And** 与 Story #001 唯一差异:`runTurn()` 不再 hard stop,**真正**进入 Action 阶段调 `toolExecutor.dispatch(call, ctx)`

2. **Given** LinearTurnEngineProvider 注入 null ExecutorService
   **When** `provider.create(cfg)` 调用
   **Then** 抛 `IllegalArgumentException("LinearTurnEngineProvider.toolPool must not be null")`(fail-fast 启动期校验)
   **And** 错误码 `LINGS-C02 CONFIG_VALIDATION_FAILED`(配置域 — 缺关键依赖)

---

### Edge Cases

- **Tool 调用超时**:`tool.parallelism` 默认 8,`tool.timeoutSeconds` 默认 60。Future.get(60, SECONDS) 超时 → ToolResult.error("tool timeout after 60s"),对应 `LINGS-T02 TOOL_TIMEOUT`(Story #004 复用 §15 错误码表,本身**不**新增 ErrorCode)
- **Cancellation 在 tool loop 中触发**:Story #005 才实现 cancellation token 注入;Story #004 在 `runTurn` 入口检查 `ctx.done()`,loop 内部不重复检查(避免 Story #004 与 #005 重复实现)
- **PermissionPolicy 返 `Decision.AskUser`**:Story #005 实现 ApprovalGate 完整回路;Story #004 在遇到 AskUser 时**直接抛 `PermissionDeniedException`**("AskUser approval flow is wired in Story #005 follow-up" — 与 `DefaultToolExecutor` 现有 stub 行为对齐,**不**新增分支)
- **空 toolCalls 数组**:`dispatchParallel([], ...)` → 返空 `ToolResult[]`,不创建 Semaphore / 不提交任何 future
- **单 tool call**:`dispatchParallel([call], parallelism=4)` → 仍走并发路径(只是单飞),wall-clock = single_latency(不因 Semaphore(4) 引入开销)
- **ToolExecutor.dispatch() 抛非 ToolException 子类的 RuntimeException**(如 NPE):Story #004 仍捕获 + 翻译为 ToolResult.error,**不**让 turn 崩
- **tool.parallelism 边界值**:1 → Semaphore(1) 串行;0 → 文档化为"等同于 -1(不限)"(与 dsh §15 LINGS-T06 描述对齐);-1 / ≤0 → 不创建 Semaphore,全部并发
- **tool.timeoutSeconds = 0**:Future.get() **不**传 timeout,等 LLM / tool 自身超时机制;这是文档约定,**不**做运行时校验
- **Spring 容器缺 ExecutorService Bean**:启动 fail-fast,`LINGS-C02`(LinearTurnEngineProvider 构造器抛 IAE)

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**:`LinearTurnEngine.runTurn` 在 LLM 返 tool calls 时**不再** hard stop,而是真正调 `toolExecutor.dispatch(call, ctx)` —— 每个 call 走完整 5-step pipeline(PermissionPolicy.check → registry lookup → timeout wrap → sandbox apply → execute)
- **FR-002**:`LinearTurnEngine` 新增 `private ToolResult[] dispatchParallel(List<ToolCall>, TurnContext)` 方法,**并发度**由 `ctx.config().getToolParallelism()` 控制(1 = 串行 / N = Semaphore(N) / ≤0 = 不限)
- **FR-003**:同 turn 多 tool call **结果按 LLM 返回的原顺序归集**写入 history(对应 dsh §14 14 决策)—— 不是按完成顺序归集
- **FR-004**:`LinearTurnEngine` 新增 3 个 final 字段:`toolExecutor` / `permissionPolicy` / `toolPool`,通过构造器注入(对应 dsh §6.1 + §7.1.3 不变项)
- **FR-005**:`LinearTurnEngineProvider` 改造为:`@Autowired` ToolExecutorRouter + PermissionPolicyRouter + `ExecutorService toolPool`(Bean name `agentToolPool`),`create()` 调 5 个 Router.resolve
- **FR-006**:默认 `ExecutorService` Bean 注册:`@Bean(name = "agentToolPool") ExecutorService` —— 线程数 = `Runtime.getRuntime().availableProcessors() * 2`,队列无界,daemon=true,线程名前缀 `lingshu-tool-`(对应 dsh §14.7 共享线程池设计)
- **FR-007**:`DefaultToolExecutor.dispatch()` 抛 `ToolException`(PermissionDenied / ToolNotFound / ToolTimeout / ToolCancelled)时,**不**直接抛给 engine,而是**翻译为 `ToolResult.error(id, message)`** 返回 —— LinearTurnEngine 看到的就是 ToolResult,无需 try-catch(对应 dsh §4.10.1 硬规则 2 + §15 LINGS-T02/T04)
- **FR-008**:`DefaultToolExecutor.dispatch()` 抛非 ToolException 子类的 RuntimeException(如 NPE / IllegalStateException)时,**同样**翻译为 `ToolResult.error(id, ex.getMessage())`,**不**让 turn 崩(对应 dsh §4.10.1 边界)
- **FR-009**:每次 tool 完成,LinearTurnEngine 推 `AgentEvent.ToolCompleted(result)` 到 sink(对应 dsh §6.1 sequenceDiagram + §14.10 AuditLog 入审计)
- **FR-010**:`DefaultToolExecutor` 5-step pipeline 完整性保留(Story #001 已有 step 1+2,Story #004 保留现状,**不**新加 sandbox/timeout wrapper —— 留 Story #016 Audit / Story #011 Retry / Story #013 Health 接力;`LinearTurnEngine.runTurn` 的 dispatchParallel 不替代 sandbox 内部计时)

### Non-Functional Requirements

- **NFR-001**:AC-03 加速比 ≥ 3.0×(4 个 1s tool,parallelism=4,wall-clock ≤ 1.3s)
- **NFR-002**:JDK 8 兼容 —— `Semaphore` / `CompletableFuture` / `TimeUnit` / `ThreadFactory` 全部 JDK 8 内置,**不**引入 Reactor / RxJava
- **NFR-003**:`LingShu-tool` 线程名前缀便于 jstack 排查(对应 dsh §14.7 共享线程池)
- **NFR-004**:无新增 Maven 依赖 —— dsh §10.1 13 项锁定 + §2 R-13 mitigation (d) `dependency:tree` 自查,**只**用 `java.util.concurrent` + Spring `ThreadPoolTaskExecutor` 包装
- **NFR-005**:`LINGS-Txx` Tool 域错误码复用(本 Story **不**新增 ErrorCode;`T02 / T04` 在 §15 已有定义);`LINGS-C02 CONFIG_VALIDATION_FAILED` 复用为启动期 fail-fast

### Key Entities

- `LinearTurnEngine`(改):新增 3 个 final 字段 + `dispatchParallel` 私有方法 + 5-step pipeline 集成
- `LinearTurnEngineProvider`(改):新增 3 个 Router + ExecutorService 注入 + 改造 `create()`
- `ToolExecutorConfig`(新):`@Configuration` 内部类,提供 `agentToolPool` Bean
- `DefaultToolExecutor`(改):`dispatch()` 增加 try-catch 翻译 ToolException → ToolResult.error(线性需求,**不**改 5-step pipeline 顺序)
- `LinearTurnEngineToolDispatchTest`(新):单元测试 + L1 Unit 覆盖(US1 + US3)
- `LinearTurnEngineParallelDispatchTest`(新):黑盒测试 + L2 Slice 覆盖(US2 AC-03 验证)
- `ReadFileTool` 等示例 Tool(新,lingshu-examples/test 目录):4 个独立 Tool 实现,sleep 1s 模拟 I/O 延迟,AC-03 加速比黑盒验证

---

## Success Criteria *(mandatory)*

- **SC-001**:`mvn -pl lingshu-core test` 全绿(L1 Unit + L2 Slice 全部通过)
- **SC-002**:`LinearTurnEngineParallelDispatchTest#blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s` 测试通过 — wall-clock ≤ 1.3s,加速比 ≥ 3.0×
- **SC-003**:`LinearTurnEngineToolDispatchTest#sequentialToolDispatch_toolResultsInOrder` 测试通过 — 4 个 tool call 顺序归集
- **SC-004**:`LinearTurnEngineProviderTest#create_returnsEngineWithAll4FieldsWired` 测试通过 — final 字段非 null 反射断言
- **SC-005**:`mvn dependency:tree` 输出与 Story #003 baseline 一致 — 0 行新增依赖(满足 §2 R-13)
- **SC-006**:PR body 末尾有 `### R-13 dependency:tree 自查` 节,贴关键子树(对比 Story #003 baseline)
