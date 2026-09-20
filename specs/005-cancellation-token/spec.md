# Feature Specification: Story #005 cancellation-token

**Feature Branch**: `story-005-cancellation-token`
**Created**: 2026-09-20
**Status**: Draft
**Input**: User description: "Story #005 cancellation-token — 三层贯通(FlowEngine / ToolExecutor / LlmProvider)+ JVM shutdown hook + 200ms in-flight stop (AC-04)"

**Source Design Doc**: `dsh_agent_design.md` v1.5.34
- §0.4 AC-04 L109-113(取消传播 200ms 内 in-flight turn 停止)
- §4.6 L663-667(CancellationToken 接口契约: `isCancelled()` + `onCancel(callback)` → Runnable)
- §6.1 LinearTurnEngine(ReAct Loop 入口检查 cancellation)
- §14.12 N12(CancellationToken 三层贯通完整设计)
- §15 LINGS-L02 LLM_STREAM_CANCELLED / LINGS-T06 TOOL_CANCELLED / LINGS-R01 REACT_CANCELLED
- §17 Risk Register R-02(取消机制缺失)

**Constitution**: `.specify/memory/constitution.md` v1.0
- §1 #12 启动时配置校验(无新增字段)
- §2 13 项依赖锁定(R-13 mitigation (d) dep-tree 自查,0 新增)
- §3 NFR:Turn 完成 P99 ≤ 60s(本 Story 引入 ≤ 200ms 取消上限,远低于该预算)
- §4 LINGS-L02 / T06 / R01 复用(0 新增 ErrorCode)
- §5 7 层金字塔(L1 Unit + L2 Slice + L5 E2E 涉及)
- §10 R-02 多租户 ThreadLocal 泄漏间接相关(本 Story 引入 ThreadLocal 取消状态需 `try-finally`)

**对应 AC**: **AC-04**(取消传播 §0.4 L109-113)— Agent 跑 30 步 turn 中,用户 Ctrl-C → 200ms 内所有 in-flight turn 停止;partial 响应 + `stopReason=CANCELLED` 已写入 §14.10 AuditLog;§14.12 CancellationToken 三层贯通验证

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Ctrl-C 立即终止 in-flight turn (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我在 IDE 里跑一个 long-running Agent turn(典型场景:让 LLM 重构一个 50 文件仓库),**期望** 我按 Ctrl-C 后,Agent **200ms 内**停掉所有 in-flight turn、写入 partial 响应 + `stopReason=CANCELLED` 到 AuditLog、然后进程退出。这样我可以快速抢回控制权,不浪费 LLM token。

**Why this priority**: 这是 **AC-04 的核心验证目标**(200ms 内停 + CANCELLED 写入 AuditLog)。Story #001—#004 完全没有取消机制 —— 用户 Ctrl-C 只是 SIGTERM 整个 JVM 进程,所有 in-flight turn 强行中断,**没有** partial 响应写入,**没有** stopReason=CANCELLED,**没有** AuditLog 痕迹;这是 LingShu 上生产部署的硬阻塞(运维重启 / 流量切换 / 用户误操作 都依赖优雅取消)。**缺它** 任何 P50 ≤ 30s / turn 完成预算的 SLO 都形同虚设 —— 用户 Ctrl-C 后不知道 turn 到底有没有停,LLM token 可能继续消耗。

**Independent Test**: 在 `lingshu-core/src/test/.../runtime/CancellationTokenIT`(集成测试,启动 Spring + 起 1 个 30 步骤 turn + 主线程发 cancel + 断言 wall-clock ≤ 200ms)加 1 个核心黑盒用例。注入 `EchoLlmProvider` 在第一次 `stream()` 调用时让 future 永远 pending,模拟 LLM 长请求;主线程启动 turn 后 50ms 调 `ctx.cancellation().fire()`,断言 (a) turn 在 200ms 内返回;(b) `RunResult.stopReason == CANCELLED`;(c) session.history 收到 partial assistant message + ToolResult(error=tool cancelled)。

**Acceptance Scenarios**:

1. **Given** Agent 正在跑 turn,LLM 调用 `fut.get()` 阻塞中(EchoLlmProvider future 永远 pending)
   **When** 另一线程调 `ctx.cancellation().fire()`
   **Then** turn 在 200ms 内返回(RunResult.stopReason=CANCELLED),不抛 RuntimeException
   **And** `TurnCompleted(stopReason=CANCELLED, usage=...)` 事件推到 sink
   **And** session.history 至少 1 条 assistant message(partial LLM 响应)写入

2. **Given** Agent 正在跑 turn,4 个 tool 并行 dispatch 中(SleepTool 1s each,parallelism=4)
   **When** 主线程 Ctrl-C 触发 `AgentFactory.broadcastCancel()` → 所有 in-flight `TurnContext.cancellation().fire()`
   **Then** 4 个 future 在 200ms 内全部 cancel(JVM exit hook 立即调)
   **And** 4 个 ToolResult 都标记 `status=ERROR, content="tool cancelled by shutdown"`,写入 session.history 原顺序位置
   **And** LinearTurnEngine 不抛 RuntimeException

3. **Given** Agent 正在跑 turn,且已 emit 3 个 assistant message
   **When** Ctrl-C 触发 cancel
   **Then** session.history 完整保留前 3 条 assistant message(部分响应写入)
   **And** 任何后续 cancel 不会清空已有 history(append-only 语义)

4. **Given** Spring 容器启动完成(0 in-flight turn)
   **When** 用户按 Ctrl-C
   **Then** JVM shutdown hook 触发 → `AgentFactory.broadcastCancel()` 调空集合 → 进程正常退出(exit 0)
   **And** 无 `NullPointerException`(空集合 forEach 是 no-op)

---

### User Story 2 — TurnContext + ToolExecutionContext 共享 cancellation 状态 (Priority: P1)

作为 **Charlie(框架贡献者)**,我在 Story #004 之上扩展,需要 `TurnContext.cancellation()` 与 `ToolExecutionContext.cancellation()` 返**同一个** CancellationToken 实例,这样 Ctrl-C 取消状态在 FlowEngine / ToolExecutor / 任何未来的 PermissionPolicy.check / ApprovalGate.ask 内部都可见。

**Why this priority**: 这是 **Story #005 的契约前提**。当前 `DefaultToolExecutionContext.cancellation()` 返 no-op stub(只读 `ctx.done()`),`onCancel(callback)` 返 unregister-noop —— **完全无法**承载 US1 的"200ms 内 stop"语义(tool 内部 SleepTool.pollCancellationToken() 永远看不到 cancel 状态,只能等自然 1s 结束)。**缺它** Story #005 的"三层贯通"只是个空头标签 —— tool 层根本没拿到 token。

**Independent Test**: 在 `lingshu-core/src/test/.../slot/CancellationTokenSharingTest`(纯 L1 Unit,不启 Spring)加 1 个用例 —— 构造 1 个 `CancellationTokens.create()` + 1 个 mock `TurnContext` 注入 token + 1 个 `DefaultToolExecutionContext(ctx)`,断言 `ctx.cancellation() == defaultToolCtx.cancellation()`(同一引用)。

**Acceptance Scenarios**:

1. **Given** 1 个 `DefaultTurnContext` 构造时持有 `CancellationTokens.create()` 返回的 token
   **And** 1 个 `DefaultToolExecutionContext(defaultTurnCtx)` 包裹它
   **When** 调 `defaultToolCtx.cancellation().isCancelled()`
   **Then** 返 `false`(尚未 fire)
   **And** `defaultToolCtx.cancellation() == defaultTurnCtx.cancellation()`(同一引用,`==` 而非 `equals`)

2. **Given** `defaultTurnCtx.cancellation().fire()` 已调
   **When** 调 `defaultToolCtx.cancellation().isCancelled()`
   **Then** 返 `true`(共享状态)

3. **Given** `defaultToolCtx.cancellation().onCancel(callback)` 注册回调
   **When** 主线程调 `fire()`
   **Then** callback 在 `fire()` 调用线程同步执行
   **And** 返 unregister Runnable,unregister 后 callback 不再触发

---

### User Story 3 — LlmProvider cancellation 透传 (Priority: P2)

作为 **Bob(业务配置方)**,我在 yml 配 `lingShu.llm.cancelOnShutdown: true`,AgentFactory 注册 shutdown hook 时把 `LLM HTTP connection.disconnect()` 也加上,这样 Anthropic API 的 in-flight streaming response 立即终止,不浪费 token。

**Why this priority**: 这是 **§14.12 三层贯通的最后一层**。当前 `AnthropicLlmProvider.stream()` 内部循环读 HttpURLConnection InputStream,**不**检查 `ctx.cancellation().isCancelled()`,因此即使 turn 已 cancel,LLM HTTP 调用仍继续直到 stream 自然结束或 server timeout。这导致 (a) LLM token 被消耗;(c) Anthropic 那边记 1 次完整响应,实际用户没收到。**优先级 P2** 因为:若 LLmProvider 不改,US1 仍然成立(turn 在 200ms 内返回,后续 audit log 正常),只是**多消耗**了 1 个 LLM stream 的尾 token;但**正确性**已满足,只是**资源浪费**。

**Independent Test**: 暂留 Story #005b(下一窗口)。本 Story 仅在 `plan.md` "Future Scope" 节标注,P2 留待 Story #005b 实施。

**Acceptance Scenarios**: (留待 Story #005b)

---

### User Story 4 — AgentFactory.broadcastCancel() 静态注册表 (Priority: P1)

作为 **Alice**,我在 Spring Boot 嵌入式部署 LingShu 时,**期望** JVM shutdown hook 自动调 `AgentFactory.broadcastCancel()` —— 我无需在每个 Spring Boot app 里手动加 hook,框架兜底。这是 §14.6 N6 graceful shutdown 的最小子集(Story #005 只做"in-flight turn 取消"这一步,完整 graceful shutdown 等 Story #013)。

**Why this priority**: 这是 **AC-04 完整实现必要条件**。AC-04 明说"用户按 Ctrl-C(JVM shutdown hook 触发)→ 200ms 内 in-flight turn 停止" —— 没有 AgentFactory 注册 JVM hook,US1 缺触发路径。**优先级 P1** 是 US1 的伴生 Story(US1 测试通过需要 broadcast 路径)。

**Independent Test**: 在 `lingshu-core/src/test/.../runtime/AgentFactoryBroadcastCancelTest`(纯 L2,不启 Spring 但用 Mockito mock AgentFactory 静态注册表)加 1 个用例 —— 模拟 1 个 in-flight turn 已注册到 broadcast registry,主线程调 `AgentFactory.broadcastCancel()`,断言 (a) 该 turn 的 token.isCancelled() == true;(b) 调空集合不抛 NPE。

**Acceptance Scenarios**:

1. **Given** AgentFactory 实例构造完成,内部 `broadcastRegistry` 为空
   **When** 调 `AgentFactory.broadcastCancel()`
   **Then** 无异常,无 NPE,forEach 是 no-op
   **And** 日志输出 `INFO broadcast cancel: 0 active turn(s)`

2. **Given** 1 个 Agent 实例创建后,`DefaultTurnContext` 自动向 AgentFactory 注册自己到 broadcast registry
   **When** 调 `AgentFactory.broadcastCancel()`
   **Then** 该 Agent 持有的 TurnContext.cancellation().isCancelled() == true
   **And** 日志输出 `INFO broadcast cancel: 1 active turn(s)`

3. **Given** JVM 启动完成(Spring 容器已初始化 AgentFactory)
   **When** 用户按 Ctrl-C(SIGTERM)
   **Then** JVM shutdown hook 触发 → `AgentFactory.broadcastCancel()` 调 → 所有 in-flight turn 在 200ms 内停 → JVM 进程正常退出

---

### Edge Cases

- **空 broadcast registry**: `broadcastCancel()` 调空集合必须 no-op,**不**抛 `NullPointerException` 或 `IllegalStateException`(US4 S1)
- **重复 `fire()`**: token 状态 idempotent —— 第二次 `fire()` 是 no-op(回调不应触发 2 次)
- **`onCancel(callback)` 回调抛异常**: 1 个 callback 抛 RuntimeException **不**阻断其它 callback 触发(每个 callback 用 try-catch 包)
- **回调在 fire() 调用线程同步执行**: 不异步、不另起线程,保证可见性 + 简化测试
- **DefaultAgent.runBlocking 主线程 cancel 自己**: 主线程调 `cancel` 同时调用 `runBlocking` —— 同步语义,主线程已同步唤醒,无死锁
- **并发多个 turn 同时 cancel**: 多个 turn 注册到 broadcast registry,`broadcastCancel()` 用 `forEach` 顺序触发,每个 turn 独立 cancel
- **shutdown hook 重复注册**: JVM 内多次 `Runtime.getRuntime().addShutdownHook()` 加同一 Thread,Hook 是 no-op 注册(每个 Thread 实例唯一)
- **turn 已 done() 后再 cancel**: `fire()` 后 token.isCancelled() == true,但 turn 已 done(),LinearTurnEngine.runTurn 看到 done() 自然退出(下一次循环检查),无需特别处理
- **Ctrl-C 在 turn 启动前**: turn 启动前 ctx.cancellation().isCancelled() == false(fire 未触发),turn 正常运行 → US1 触发 Ctrl-C 后 fire → US1 S1 路径

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**:`TurnContext` 接口新增方法 `CancellationToken cancellation()`(对应 dsh §14.12 三层贯通的 engine 层入口)
- **FR-002**:`DefaultTurnContext` 构造时接收 `CancellationToken` 实例作为 final 字段,**且**构造后自动调 `AgentFactory.registerCancellation(token)` 把 token 注册到 broadcast registry(对应 US4 S2)
- **FR-003**:`DefaultTurnContext` 提供静态工厂 `DefaultTurnContext.createWithBroadcast(Session, AgentConfig, Subscriber, String)` 封装"创建 token + 注册 + 返 ctx"流程
- **FR-004**:`DefaultToolExecutionContext.cancellation()` **不再**返 no-op stub,改为 `return turnCtx.cancellation()`(对应 US2 S1 + dsh §4.6 + §14.12 tool 层入口)
- **FR-005**:新增 `CancellationTokens` 工具类(包 `ai.lingshu.core.impl.concurrent`),提供:
  - `static CancellationToken create()` 返可 fire 的 token(共享状态、可注册回调)
  - **实现**:`SimpleCancellationToken` 内部类,基于 `AtomicBoolean cancelled` + `CopyOnWriteArrayList<Runnable> callbacks`,JDK 8 内置,**不**引依赖
  - `fire()` 同步触发所有 callbacks,幂等
  - `onCancel(callback)` 注册回调,返 unregister Runnable
- **FR-006**:`LinearTurnEngine.runTurn` 在循环头(`while (!ctx.done())`)**新增** `if (ctx.cancellation().isCancelled()) { ctx.markDone(); emit TurnCompleted(CANCELLED, ...); break; }` 检查(对应 dsh §14.12 FlowEngine 入口检查)
- **FR-007**:`LinearTurnEngine.runTurn` 在 `fut.get()` 等 LLM 响应处**新增** 200ms 短超时 fallback —— `fut.get(200, MILLISECONDS)` 抛 `TimeoutException` 时 cancel future + emit `TurnCompleted(CANCELLED, partialUsage)` + break(对应 AC-04 200ms)
- **FR-008**:`LinearTurnEngine.dispatchParallel` 在 `futures[i].get(timeoutSec, SECONDS)` 等待处**新增** `if (ctx.cancellation().isCancelled()) futures[i].cancel(true)` 轮询 —— `TimeoutException` catch 块扩展为 `CancellationException` 也归为 `ToolResult.error(content="tool cancelled by shutdown")`(对应 dsh §14.12 Tool 层 + AC-04 partial 响应写 history)
- **FR-009**:`AgentFactory` 新增方法 `broadcastCancel()`,内部维护 `CopyOnWriteArrayList<CancellationToken> broadcastRegistry` + `registerCancellation(CancellationToken)` 注册方法,**且**构造完成后立即 `Runtime.getRuntime().addShutdownHook(new Thread(this::broadcastCancel, "lingshu-shutdown-cancel"))`(对应 AC-04 JVM shutdown hook + US4)
- **FR-010**:`AgentFactory` 在每次 `create(config)` 时返 `DefaultAgent` 之前调 `DefaultTurnContext.createWithBroadcast(...)` 而**非**直接 `new DefaultTurnContext(...)` —— DefaultAgent.buildContext 同步改造(对应 FR-003)
- **FR-011**:`DefaultAgent.buildContext` 改造为调 `DefaultTurnContext.createWithBroadcast(session, config, sink, userInput)` 而非 `new DefaultTurnContext(...)`(对应 FR-010)
- **FR-012**:`CancellationToken` 接口不变(dsh §4.6 L663-667 已固化: `isCancelled()` + `onCancel(Runnable) → Runnable`),**只**新增 `default void fire()` 默认方法无操作让接口向后兼容 —— 实际 token impl 必须 override `fire()`(对应 JDK 8 default method)
- **FR-013**:`CancellationToken.fire()` 加入接口签名:`default void fire() {}` —— SimpleCancellationToken override 为原子 set + 触发 callbacks(对应 FR-005 + FR-012 配套)
- **FR-014**:`CancellationToken` 接口 Javadoc 明确"fire() 是协作式取消触发点 —— 取消是协作的,impl 必须 polling `isCancelled()` 或注册 callback 才感知;非 interrupt"(对应 dsh §14.12 协作式语义)

### Non-Functional Requirements

- **NFR-001**:AC-04 200ms 内 in-flight turn 停止 —— `LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms` 集成测试通过
- **NFR-002**:JDK 8 兼容 —— `AtomicBoolean` / `CopyOnWriteArrayList` / `default` interface method(Java 8+)全部 JDK 8 内置,**不**引入 Reactor / RxJava / Guava
- **NFR-003**:无新增 Maven 依赖 —— dsh §10.1 13 项锁定 + §2 R-13 mitigation (d) `dependency:tree` 自查,**只**用 `java.util.concurrent.atomic` + `java.util.concurrent.CopyOnWriteArrayList`
- **NFR-004**:`LINGS-L02 LLM_STREAM_CANCELLED` / `LINGS-T06 TOOL_CANCELLED` / `LINGS-R01 REACT_CANCELLED` 复用(本 Story **不**新增 ErrorCode;`L02 / T06 / R01` 在 §15 已有定义)
- **NFR-005**:turn 已 done() 后再 fire() 是 no-op(idempotent);多次 fire 不重复触发 callbacks(FR-005 配套)
- **NFR-006**:`broadcastCancel()` 同步执行所有 callbacks(不另起线程),保证可见性 + 测试确定性
- **NFR-007**:JVM shutdown hook 用 daemon-ish 独立 Thread(`Thread("lingshu-shutdown-cancel")`),不阻塞 JVM 退出,但 hold 主线程直到 callbacks 跑完(默认 200ms)
- **NFR-008**:broadcast registry 用 `CopyOnWriteArrayList` —— 高并发注册安全 + 迭代过程中修改不抛 `ConcurrentModificationException`(对应 FR-009)

### Key Entities

- `CancellationTokens` (new):utility class + inner `SimpleCancellationToken` impl
- `CancellationToken` (modify):interface add `default void fire()` method
- `TurnContext` (modify):interface add `cancellation()` method
- `DefaultTurnContext` (modify):final field + static `createWithBroadcast` factory + implements `cancellation()`
- `DefaultToolExecutionContext` (modify):`cancellation()` 返 `turnCtx.cancellation()`
- `LinearTurnEngine` (modify):loop head check + 200ms timeout fallback + dispatchParallel cancel poll
- `AgentFactory` (modify):broadcastRegistry + `registerCancellation` + `broadcastCancel` + JVM shutdown hook
- `DefaultAgent` (modify):`buildContext` 调 `createWithBroadcast`

**Total**:1 new + 6 modified = **7 文件改动**,**轻度超** CLAUDE.md §11 #4 "≤ 5 核心文件改动" 软上限

**超上限原因**:
1. Story #005 by nature 触及三层(FlowEngine / ToolExecutor / AgentFactory)+ 1 个新接口 impl + 2 个接口(AgentEvent 不动,TurnContext 加方法)+ 1 个 DefaultTurnContext 改造,7 文件是三层贯通最少集
2. **不**触 AnthropicLlmProvider(留 Story #005b,US3 P2 deferred)
3. **不**触 AgentEvent(用现有 `StopReason.CANCELLED` enum)
4. **不**触 DefaultAgent 大量代码(只改 buildContext 1 个 method)

如果想严格 ≤ 5:把 `DefaultToolExecutionContext.cancellation()` 改造**合并**到 `DefaultTurnContext`(在 DefaultTurnContext 内部维护一个对 ToolExecutionContext 的 cancellation alias 字段)→ 但这破坏 TurnContext / ToolExecutionContext 接口语义边界,**不**推荐

---

## Success Criteria *(mandatory)*

- **SC-001**:`mvn -pl lingshu-core test` 全绿(L1 Unit + L2 Slice + L5 E2E 全部通过)
- **SC-002**:`LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms` 测试通过 — 主线程 cancel 后 turn 在 200ms 内返回,`RunResult.stopReason == CANCELLED`
- **SC-003**:`CancellationTokenSharingTest#toolExecutionContext_sharesSameTokenAsTurnContext` 测试通过 — `defaultToolCtx.cancellation() == defaultTurnCtx.cancellation()`
- **SC-004**:`AgentFactoryBroadcastCancelTest#broadcastCancel_multipleTurns_allCancelled` 测试通过 — broadcast 触发所有 in-flight turn 的 token
- **SC-005**:`mvn dependency:tree` 输出与 Story #004 baseline 一致 — 0 行新增依赖(满足 §2 R-13)
- **SC-006**:PR body 末尾有 `### R-13 dependency:tree 自查` 节,贴关键子树(对比 Story #004 baseline)
- **SC-007**:无回归 —— Story #001—#004 全部 16 测试用例仍 green